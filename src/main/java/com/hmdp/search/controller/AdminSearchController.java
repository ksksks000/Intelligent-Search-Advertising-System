package com.hmdp.search.controller;

import com.hmdp.dto.Result;
import com.hmdp.search.embedding.EmbeddingClient;
import com.hmdp.search.index.SearchIndexService;
import com.hmdp.search.service.SearchHotWordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端搜索索引接口，由 AdminAuthInterceptor 校验 X-Admin-Token。
 */
@RestController
@RequestMapping("/admin/search")
public class AdminSearchController {

    @Resource
    private SearchIndexService searchIndexService;

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private SearchHotWordService hotWordService;

    @PostMapping("/reindex")
    public Result reindex() {
        return Result.ok(searchIndexService.reindexAll());
    }

    @GetMapping("/stats")
    public Result stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("alias", searchIndexService.getAlias());
        stats.put("docCount", searchIndexService.count());
        stats.put("embeddingHealthy", embeddingClient.isHealthy());
        return Result.ok(stats);
    }

    /**
     * 手动触发热词衰减，便于验证定时任务逻辑。
     */
    @PostMapping("/hot/decay")
    public Result decayHotWords() {
        hotWordService.decay();
        return Result.ok(hotWordService.list(10));
    }
}
