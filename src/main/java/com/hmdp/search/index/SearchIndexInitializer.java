package com.hmdp.search.index;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
@Order(2)
public class SearchIndexInitializer implements ApplicationRunner {

    @Resource
    private SearchIndexService searchIndexService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            searchIndexService.ensureIndex();
        } catch (Exception e) {
            // ES 未就绪不阻塞应用启动；搜索接口会返回降级/空结果
            log.error("搜索索引初始化失败，ES 可能未就绪；可通过 POST /admin/search/reindex 重试", e);
        }
    }
}
