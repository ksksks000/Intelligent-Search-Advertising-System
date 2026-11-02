package com.hmdp.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.Activity;
import com.hmdp.search.document.ActivityDocument;
import com.hmdp.search.dto.ReindexResult;
import com.hmdp.search.embedding.EmbeddingClient;
import com.hmdp.service.IActivityService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import javax.annotation.Resource;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SearchIndexService {

    @Value("${hmdp.es.index-alias:activity_index}")
    private String alias;

    @Value("${hmdp.es.index-initial-version:v1}")
    private String initialVersion;

    /** 低内存机器可通过 hmdp.embedding.batch-size 降低重建批量，避免 embedding CPU OOM */
    @Value("${hmdp.embedding.batch-size:32}")
    private int embeddingBatchSize;

    @Resource
    private ElasticsearchClient client;

    @Resource
    private IActivityService activityService;

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private StringRedisTemplate redis;

    /**
     * 幂等创建初始索引与别名。
     */
    public void ensureIndex() throws Exception {
        if (aliasExists()) {
            return;
        }
        String index = alias + "_" + initialVersion;
        if (!indexExists(index)) {
            createIndex(index);
        }
        addAlias(index);
        log.info("搜索索引初始化完成，index={}, alias={}", index, alias);
    }

    /**
     * 全量重建：新索引写完后原子切换别名。
     * 重建期间增量事件通过 search:rebuild:target 双写到新索引，避免丢失。
     */
    public ReindexResult reindexAll() {
        long start = System.currentTimeMillis();
        String newIndex = alias + "_v" + start;
        boolean switched = false;
        try {
            createIndex(newIndex);
            // 先标记重建目标，再读 DB；此后的增量事件会双写
            redis.opsForValue().set(RedisConstants.SEARCH_REBUILD_TARGET_KEY, newIndex,
                    RedisConstants.SEARCH_REBUILD_TARGET_TTL_SECONDS, TimeUnit.SECONDS);
            long count = indexAllActivities(newIndex);
            client.indices().refresh(r -> r.index(newIndex));
            Set<String> oldIndices = aliasIndices();
            switchAlias(oldIndices, newIndex);
            switched = true;
            deleteIndicesQuietly(oldIndices);

            ReindexResult result = new ReindexResult();
            result.setIndex(newIndex);
            result.setDocCount(count);
            result.setElapsedMs(System.currentTimeMillis() - start);
            log.info("搜索索引重建完成，index={}, docs={}, elapsed={}ms",
                    newIndex, count, result.getElapsedMs());
            return result;
        } catch (Exception e) {
            if (!switched) {
                deleteIndexQuietly(newIndex);
            }
            throw new IllegalStateException("索引重建失败: " + e.getMessage(), e);
        } finally {
            redis.delete(RedisConstants.SEARCH_REBUILD_TARGET_KEY);
        }
    }

    /**
     * 增量 upsert：同时写别名与重建目标索引（若存在）。
     * embedding 失败时仍写入倒排字段，保证搜索结果可用并记录降级。
     */
    public void upsert(Activity activity) {
        float[] vector = null;
        long embeddingStart = System.currentTimeMillis();
        try {
            vector = embeddingClient.encodeDocuments(
                    Collections.singletonList(toEmbeddingText(activity))).get(0);
        } catch (Exception e) {
            log.warn("增量索引 embedding 失败，降级为纯倒排，activityId={}, cost={}ms",
                    activity.getId(), System.currentTimeMillis() - embeddingStart);
        }
        long embeddingMs = System.currentTimeMillis() - embeddingStart;
        ActivityDocument document = toDocument(activity, vector);
        long esStart = System.currentTimeMillis();
        try {
            indexDocument(alias, document);
            String target = redis.opsForValue().get(RedisConstants.SEARCH_REBUILD_TARGET_KEY);
            if (target != null && !target.isEmpty() && !target.equals(alias)) {
                indexDocument(target, document);
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入搜索索引失败, activityId=" + activity.getId(), e);
        }
        log.info("search index upsert timing activityId={}, embedding={}ms, es={}ms, vector={}",
                activity.getId(), embeddingMs, System.currentTimeMillis() - esStart, vector != null);
    }

    public void delete(Long activityId) {
        long start = System.currentTimeMillis();
        try {
            client.delete(d -> d.index(alias).id(String.valueOf(activityId)));
        } catch (Exception e) {
            log.warn("删除索引文档失败（可能不存在），activityId={}, error={}", activityId, e.getMessage());
        }
        String target = redis.opsForValue().get(RedisConstants.SEARCH_REBUILD_TARGET_KEY);
        if (target != null && !target.isEmpty() && !target.equals(alias)) {
            try {
                client.delete(d -> d.index(target).id(String.valueOf(activityId)));
            } catch (Exception ignored) {
                // 新索引可能还没有该文档
            }
        }
        log.info("search index delete timing activityId={}, es={}ms",
                activityId, System.currentTimeMillis() - start);
    }

    public long count() {
        try {
            return client.count(c -> c.index(alias)).count();
        } catch (Exception e) {
            log.warn("统计搜索索引文档失败: {}", e.getMessage());
            return 0L;
        }
    }

    public String getAlias() {
        return alias;
    }

    private long indexAllActivities(String targetIndex) throws Exception {
        int pageNo = 1;
        int pageSize = 500;
        int batchSize = Math.max(1, embeddingBatchSize);
        long count = 0;
        while (true) {
            Page<Activity> page = activityService.page(new Page<>(pageNo, pageSize),
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<Activity>lambdaQuery()
                            .eq(Activity::getStatus, Activity.STATUS_ONLINE)
                            .orderByAsc(Activity::getId));
            List<Activity> records = page.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            for (int from = 0; from < records.size(); from += batchSize) {
                List<Activity> chunk = records.subList(from, Math.min(from + batchSize, records.size()));
                List<String> texts = chunk.stream().map(this::toEmbeddingText).collect(Collectors.toList());
                List<float[]> vectors = embeddingClient.encodeDocuments(texts);
                BulkRequest.Builder bulk = new BulkRequest.Builder();
                for (int i = 0; i < chunk.size(); i++) {
                    ActivityDocument document = toDocument(chunk.get(i), vectors.get(i));
                    bulk.operations(op -> op.index(idx -> idx
                            .index(targetIndex)
                            .id(String.valueOf(document.getId()))
                            .document(document)));
                }
                BulkResponse response = client.bulk(bulk.build());
                if (response.errors()) {
                    for (BulkResponseItem item : response.items()) {
                        if (item.error() != null) {
                            throw new IllegalStateException("bulk 写入失败: " + item.error().reason());
                        }
                    }
                }
                count += chunk.size();
            }
            if (records.size() < pageSize) {
                break;
            }
            pageNo++;
        }
        return count;
    }

    private ActivityDocument toDocument(Activity activity, float[] vector) {
        ActivityDocument.Location location = null;
        if (activity.getX() != null && activity.getY() != null) {
            location = new ActivityDocument.Location(activity.getY(), activity.getX());
        }
        return ActivityDocument.builder()
                .id(activity.getId())
                .title(activity.getTitle())
                .subTitle(activity.getSubTitle())
                .description(activity.getDescription())
                .images(activity.getImages())
                .tags(splitTags(activity.getTags()))
                .categoryId(activity.getCategoryId())
                .shopId(activity.getShopId())
                .activityType(activity.getActivityType())
                .city(activity.getCity())
                .area(activity.getArea())
                .location(location)
                .beginTime(toMillis(activity.getBeginTime()))
                .endTime(toMillis(activity.getEndTime()))
                .status(activity.getStatus())
                .qualityScore(activity.getQualityScore())
                .payValue(activity.getPayValue())
                .originalPrice(activity.getOriginalPrice())
                .embedding(vector)
                .build();
    }

    private void indexDocument(String index, ActivityDocument document) throws Exception {
        client.index(i -> i.index(index)
                .id(String.valueOf(document.getId()))
                .document(document));
    }

    private void createIndex(String index) throws Exception {
        String mapping = readMapping();
        CreateIndexRequest.Builder builder = new CreateIndexRequest.Builder();
        builder.index(index).withJson(new StringReader(mapping));
        client.indices().create(builder.build());
    }

    private void addAlias(String index) throws Exception {
        String body = "{\"actions\":[{\"add\":{\"index\":\"" + index
                + "\",\"alias\":\"" + alias + "\",\"is_write_index\":true}}]}";
        UpdateAliasesRequest.Builder builder = new UpdateAliasesRequest.Builder();
        builder.withJson(new StringReader(body));
        client.indices().updateAliases(builder.build());
    }

    private void switchAlias(Set<String> oldIndices, String newIndex) throws Exception {
        List<String> actions = new ArrayList<>();
        for (String old : oldIndices) {
            actions.add("{\"remove\":{\"index\":\"" + old + "\",\"alias\":\"" + alias + "\"}}");
        }
        actions.add("{\"add\":{\"index\":\"" + newIndex + "\",\"alias\":\"" + alias
                + "\",\"is_write_index\":true}}");
        String body = "{\"actions\":[" + String.join(",", actions) + "]}";
        UpdateAliasesRequest.Builder builder = new UpdateAliasesRequest.Builder();
        builder.withJson(new StringReader(body));
        client.indices().updateAliases(builder.build());
        log.info("搜索别名切换完成，{} -> {}", oldIndices, newIndex);
    }

    private boolean aliasExists() {
        try {
            return client.indices().existsAlias(e -> e.name(alias)).value();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean indexExists(String index) {
        try {
            return client.indices().exists(e -> e.index(index)).value();
        } catch (Exception e) {
            return false;
        }
    }

    private Set<String> aliasIndices() {
        try {
            Map<String, ?> result = client.indices().getAlias(g -> g.name(alias)).result();
            return new LinkedHashSet<>(result.keySet());
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    private void deleteIndicesQuietly(Set<String> indices) {
        for (String index : indices) {
            deleteIndexQuietly(index);
        }
    }

    private void deleteIndexQuietly(String index) {
        try {
            client.indices().delete(d -> d.index(index));
        } catch (Exception e) {
            log.warn("删除索引失败（可能不存在），index={}, error={}", index, e.getMessage());
        }
    }

    private String readMapping() throws Exception {
        ClassPathResource resource = new ClassPathResource("es/activity_index.json");
        try (java.io.InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(tags.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private String toEmbeddingText(Activity activity) {
        StringBuilder sb = new StringBuilder();
        append(sb, activity.getTitle());
        append(sb, activity.getSubTitle());
        append(sb, activity.getDescription());
        append(sb, activity.getTags());
        append(sb, activity.getCity());
        append(sb, activity.getArea());
        return sb.toString().trim();
    }

    private void append(StringBuilder sb, String text) {
        if (text != null && !text.trim().isEmpty()) {
            sb.append(text.trim()).append(' ');
        }
    }

    private Long toMillis(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
