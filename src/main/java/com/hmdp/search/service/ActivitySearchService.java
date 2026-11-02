package com.hmdp.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.Activity;
import com.hmdp.search.document.ActivityDocument;
import com.hmdp.search.dto.SearchItem;
import com.hmdp.search.dto.SearchRequestDTO;
import com.hmdp.search.dto.SearchResultDTO;
import com.hmdp.search.embedding.EmbeddingClient;
import com.hmdp.search.embedding.EmbeddingClient.EmbeddingException;
import com.hmdp.search.index.SearchIndexService;
import com.hmdp.utils.RedisConstants;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 混合检索：倒排 match_phrase + keyword 过滤 + dense_vector kNN。
 * 两路各取 TopN，按 activityId 去重后加权排序。
 */
@Slf4j
@Service
public class ActivitySearchService {

    @Value("${hmdp.search.lexical-top-n:50}")
    private int lexicalTopN;

    @Value("${hmdp.search.vector-top-n:50}")
    private int vectorTopN;

    @Resource
    private ElasticsearchClient client;

    @Resource
    private SearchIndexService indexService;

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private SearchHotWordService hotWordService;

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private MeterRegistry meterRegistry;

    @Value("${hmdp.search.embedding-timeout-ms:200}")
    private long embeddingTimeoutMs;

    public SearchResultDTO search(SearchRequestDTO request) {
        long start = System.currentTimeMillis();
        SearchRequestDTO req = normalize(request);
        SearchResultDTO result = new SearchResultDTO();
        result.setDegraded(false);
        result.setFallback(false);

        String query = req.getQuery();
        if (query == null || query.trim().isEmpty()) {
            return fallback(req.getSize(), start, result);
        }
        hotWordService.record(query);

        try {
            List<Query> filters = buildFilters(req);
            Map<Long, Candidate> merged = new LinkedHashMap<>();
            mergeLexical(req, query, filters, merged);
            boolean degraded = mergeVector(req, query, filters, merged, result);
            result.setDegraded(degraded);

            if (merged.isEmpty()) {
                return fallback(req.getSize(), start, result);
            }
            List<SearchItem> items = rankAndPage(merged, req);
            result.setList(items);
            result.setTotal((long) merged.size());
            result.setCostMs(System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            // ES 不可用或查询异常：返回空结果并标记降级，不把异常抛给前端
            log.error("搜索执行失败，query={}", query, e);
            result.setList(Collections.emptyList());
            result.setTotal(0L);
            result.setDegraded(true);
            result.setCostMs(System.currentTimeMillis() - start);
            return result;
        }
    }

    public SearchResultDTO fallback(int size) {
        SearchResultDTO result = new SearchResultDTO();
        result.setDegraded(false);
        return fallback(size, System.currentTimeMillis(), result);
    }

    private SearchResultDTO fallback(int size, long start, SearchResultDTO result) {
        int limit = Math.max(1, Math.min(size, 50));
        try {
            SortOptions sort = SortOptions.of(so -> so
                    .field(f -> f.field("qualityScore").order(SortOrder.Desc)));
            SearchResponse<ActivityDocument> response = client.search(s -> s
                    .index(indexService.getAlias())
                    .query(q -> q.term(t -> t.field("status")
                            .value(Activity.STATUS_ONLINE)))
                    .sort(Collections.singletonList(sort))
                    .size(limit), ActivityDocument.class);
            List<SearchItem> items = new ArrayList<>();
            for (Hit<ActivityDocument> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    items.add(toItem(hit.source(), 0D, null));
                }
            }
            result.setList(items);
            result.setTotal((long) items.size());
            result.setFallback(true);
            result.setCostMs(System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("搜索兜底查询失败", e);
            result.setList(Collections.emptyList());
            result.setTotal(0L);
            result.setFallback(true);
            result.setDegraded(true);
            result.setCostMs(System.currentTimeMillis() - start);
            return result;
        }
    }

    private void mergeLexical(SearchRequestDTO req, String query, List<Query> filters,
                              Map<Long, Candidate> merged) throws Exception {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        filters.forEach(bool::filter);
        bool.should(
                Query.of(q -> q.matchPhrase(m -> m.field("title").query(query).slop(1).boost(3.0f))),
                Query.of(q -> q.matchPhrase(m -> m.field("subTitle").query(query).slop(1).boost(2.0f))),
                Query.of(q -> q.match(m -> m.field("description").query(query).boost(1.0f)))
        ).minimumShouldMatch("1");

        SearchResponse<ActivityDocument> response = client.search(s -> s
                .index(indexService.getAlias())
                .query(Query.of(q -> q.bool(bool.build())))
                .highlight(h -> h
                        .fields("title", f -> f)
                        .fields("subTitle", f -> f)
                        .preTags(Collections.singletonList("<em>"))
                        .postTags(Collections.singletonList("</em>")))
                .size(lexicalTopN), ActivityDocument.class);

        List<Hit<ActivityDocument>> hits = response.hits().hits();
        double maxScore = 0D;
        for (Hit<ActivityDocument> hit : hits) {
            if (hit.score() != null) {
                maxScore = Math.max(maxScore, hit.score());
            }
        }
        for (Hit<ActivityDocument> hit : hits) {
            ActivityDocument doc = hit.source();
            if (doc == null || doc.getId() == null) {
                continue;
            }
            double normalized = maxScore <= 0 ? 0D : safe(hit.score()) / maxScore;
            Candidate candidate = merged.computeIfAbsent(doc.getId(), id -> new Candidate(doc));
            candidate.doc = doc;
            candidate.lexical = Math.max(candidate.lexical, normalized);
            candidate.highlight = firstHighlight(hit.highlight(), "title");
        }
    }

    /**
     * @return true 表示走向量召回时发生降级
     */
    private boolean mergeVector(SearchRequestDTO req, String query, List<Query> filters,
                                Map<Long, Candidate> merged, SearchResultDTO result) {
        VectorLookup lookup = queryVector(query);
        result.setQueryVectorCacheHit(lookup.cacheHit);
        result.setVectorDegraded(lookup.vector == null);
        result.setLexicalOnly(lookup.vector == null);
        if (lookup.vector == null) {
            meterRegistry.counter("search.vector.degraded").increment();
            log.warn("DEGRADED: vector unavailable, lexical only, query={}", query);
            return true;
        }
        try {
            float[] vector = lookup.vector;
            Query filter = Query.of(q -> q.bool(b -> {
                filters.forEach(b::filter);
                return b;
            }));
            KnnQuery knn = KnnQuery.of(k -> k
                    .field("embedding")
                    .queryVector(toFloatList(vector))
                    .k(vectorTopN)
                    .numCandidates(vectorTopN * 2L)
                    .filter(filter));
            SearchResponse<ActivityDocument> response = client.search(s -> s
                    .index(indexService.getAlias())
                    .knn(knn)
                    .size(vectorTopN), ActivityDocument.class);
            for (Hit<ActivityDocument> hit : response.hits().hits()) {
                ActivityDocument doc = hit.source();
                if (doc == null || doc.getId() == null) {
                    continue;
                }
                double similarity = Math.max(0D, Math.min(1D, safe(hit.score())));
                Candidate candidate = merged.computeIfAbsent(doc.getId(), id -> new Candidate(doc));
                candidate.doc = doc;
                candidate.vector = Math.max(candidate.vector, similarity);
            }
            return false;
        } catch (Exception e) {
            result.setVectorDegraded(true);
            result.setLexicalOnly(true);
            meterRegistry.counter("search.vector.degraded").increment();
            log.warn("DEGRADED: vector kNN failed, lexical only, query={}, error={}",
                    query, e.getMessage());
            return true;
        }
    }

    private VectorLookup queryVector(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        String key = RedisConstants.SEARCH_QUERY_VEC_KEY + sha256(normalized);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                meterRegistry.counter("search.query.cache", "result", "hit").increment();
                return new VectorLookup(objectMapper.readValue(cached, float[].class), true);
            }
        } catch (Exception e) {
            log.debug("读取 query 向量缓存失败: {}", e.getMessage());
        }
        meterRegistry.counter("search.query.cache", "result", "miss").increment();
        try {
            float[] vector = embeddingClient.encodeQueryWithTimeout(query, embeddingTimeoutMs);
            if (vector == null || vector.length == 0) {
                return new VectorLookup(null, false);
            }
            try {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(vector),
                        1, TimeUnit.HOURS);
            } catch (Exception e) {
                log.debug("写 query 向量缓存失败: {}", e.getMessage());
            }
            return new VectorLookup(vector, false);
        } catch (Exception e) {
            log.warn("DEGRADED: query embedding failed, query={}, error={}", query, e.getMessage());
            return new VectorLookup(null, false);
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static final class VectorLookup {
        private final float[] vector;
        private final boolean cacheHit;

        private VectorLookup(float[] vector, boolean cacheHit) {
            this.vector = vector;
            this.cacheHit = cacheHit;
        }
    }

    private List<SearchItem> rankAndPage(Map<Long, Candidate> merged, SearchRequestDTO req) {
        List<Candidate> candidates = new ArrayList<>(merged.values());
        for (Candidate candidate : candidates) {
            double quality = candidate.doc.getQualityScore() == null
                    ? 0D : Math.min(1D, candidate.doc.getQualityScore() / 100D);
            boolean hasVector = candidate.vector > 0D;
            candidate.finalScore = hasVector
                    ? 0.55D * candidate.lexical + 0.35D * candidate.vector + 0.10D * quality
                    : 0.85D * candidate.lexical + 0.15D * quality;
        }
        candidates.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));

        int from = (req.getCurrent() - 1) * req.getSize();
        List<SearchItem> items = new ArrayList<>();
        if (from >= candidates.size()) {
            return items;
        }
        int to = Math.min(from + req.getSize(), candidates.size());
        for (Candidate candidate : candidates.subList(from, to)) {
            items.add(toItem(candidate.doc, candidate.finalScore, candidate.highlight));
        }
        return items;
    }

    private SearchItem toItem(ActivityDocument doc, double score, String highlight) {
        SearchItem item = new SearchItem();
        item.setId(doc.getId());
        item.setTitle(doc.getTitle());
        item.setSubTitle(doc.getSubTitle());
        item.setImages(doc.getImages());
        item.setActivityType(doc.getActivityType());
        item.setCategoryId(doc.getCategoryId());
        item.setShopId(doc.getShopId());
        item.setCity(doc.getCity());
        item.setArea(doc.getArea());
        item.setPayValue(doc.getPayValue());
        item.setOriginalPrice(doc.getOriginalPrice());
        item.setQualityScore(doc.getQualityScore());
        item.setScore(Math.round(score * 10000D) / 10000D);
        item.setHighlightTitle(highlight == null ? doc.getTitle() : highlight);
        return item;
    }

    private List<Query> buildFilters(SearchRequestDTO req) {
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t.field("status")
                .value(Activity.STATUS_ONLINE))));
        if (req.getCategoryId() != null) {
            filters.add(Query.of(q -> q.term(t -> t.field("categoryId")
                    .value(req.getCategoryId()))));
        }
        if (req.getActivityType() != null) {
            filters.add(Query.of(q -> q.term(t -> t.field("activityType")
                    .value(req.getActivityType().longValue()))));
        }
        if (req.getCity() != null && !req.getCity().trim().isEmpty()) {
            filters.add(Query.of(q -> q.term(t -> t.field("city")
                    .value(req.getCity().trim()))));
        }
        if (req.getMinPayValue() != null || req.getMaxPayValue() != null) {
            filters.add(Query.of(q -> q.range(r -> {
                r.field("payValue");
                if (req.getMinPayValue() != null) {
                    r.gte(JsonData.of(req.getMinPayValue()));
                }
                if (req.getMaxPayValue() != null) {
                    r.lte(JsonData.of(req.getMaxPayValue()));
                }
                return r;
            })));
        }
        return filters;
    }

    private SearchRequestDTO normalize(SearchRequestDTO request) {
        SearchRequestDTO req = request == null ? new SearchRequestDTO() : request;
        if (req.getCurrent() == null || req.getCurrent() < 1) {
            req.setCurrent(1);
        }
        if (req.getSize() == null || req.getSize() < 1) {
            req.setSize(10);
        }
        req.setSize(Math.min(req.getSize(), 50));
        return req;
    }

    private String firstHighlight(Map<String, List<String>> highlight, String field) {
        if (highlight == null) {
            return null;
        }
        List<String> fragments = highlight.get(field);
        return fragments == null || fragments.isEmpty() ? null : fragments.get(0);
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float value : vector) {
            list.add(value);
        }
        return list;
    }

    private double safe(Double value) {
        return value == null ? 0D : value;
    }

    private static class Candidate {
        private ActivityDocument doc;
        private double lexical;
        private double vector;
        private double finalScore;
        private String highlight;

        private Candidate(ActivityDocument doc) {
            this.doc = doc;
        }
    }
}
