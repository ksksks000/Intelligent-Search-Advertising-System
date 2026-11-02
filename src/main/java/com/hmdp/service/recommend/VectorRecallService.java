package com.hmdp.service.recommend;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.ActivityCandidate;
import com.hmdp.dto.RecommendContext;
import com.hmdp.entity.Activity;
import com.hmdp.search.document.ActivityDocument;
import com.hmdp.search.index.SearchIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 向量召回：用户兴趣向量 vs activity_index.embedding，直接 ES kNN。
 * 不调用 ActivitySearchService，避免搜索排序干扰推荐。
 */
@Slf4j
@Service
public class VectorRecallService implements RecallService {

    @Resource
    private ElasticsearchClient client;

    @Resource
    private SearchIndexService searchIndexService;

    @Resource
    private InterestVectorService interestVectorService;

    @Resource
    private RecommendProperties properties;

    @Override
    public String source() {
        return "VECTOR";
    }

    @Override
    public List<ActivityCandidate> recall(RecommendContext context) {
        long embeddingStart = System.currentTimeMillis();
        float[] vector = interestVectorService.getVector(context);
        context.getStageCosts().put("recall.VECTOR.embedding",
                System.currentTimeMillis() - embeddingStart);
        if (vector == null || vector.length == 0) {
            return new ArrayList<>();
        }
        try {
            Query filter = Query.of(q -> q.bool(b -> {
                b.filter(f -> f.term(t -> t.field("status").value(Activity.STATUS_ONLINE)));
                if (context.getCity() != null && !context.getCity().trim().isEmpty()) {
                    b.filter(f -> f.term(t -> t.field("city").value(context.getCity().trim())));
                }
                return b;
            }));
            int topN = Math.max(1, properties.getVectorTopN());
            KnnQuery knn = KnnQuery.of(k -> k
                    .field("embedding")
                    .queryVector(toFloatList(vector))
                    .k(topN)
                    .numCandidates((long) topN * 2)
                    .filter(filter));
            long esStart = System.currentTimeMillis();
            // 只取 _id，不拉取包含 512 维 embedding 的 _source（50 条约数百 KB，反序列化会超出 50ms）
            SearchResponse<ActivityDocument> response = client.search(s -> s
                    .index(searchIndexService.getAlias())
                    .source(src -> src.fetch(false))
                    .knn(knn)
                    .size(topN), ActivityDocument.class);
            context.getStageCosts().put("recall.VECTOR.es",
                    System.currentTimeMillis() - esStart);
            List<ActivityCandidate> candidates = new ArrayList<>();
            for (Hit<ActivityDocument> hit : response.hits().hits()) {
                if (hit.id() == null) {
                    continue;
                }
                Long activityId;
                try {
                    activityId = Long.parseLong(hit.id());
                } catch (NumberFormatException e) {
                    continue;
                }
                double score = hit.score() == null ? 0D : hit.score();
                candidates.add(ActivityCandidate.of(activityId, source(),
                        Math.max(0D, Math.min(1D, score))));
            }
            return candidates;
        } catch (Exception e) {
            context.getDegradedSources().add("VECTOR");
            log.warn("DEGRADED: vector recall failed, userKey={}, error={}",
                    context.getUserKey(), e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float value : vector) {
            list.add(value);
        }
        return list;
    }
}
