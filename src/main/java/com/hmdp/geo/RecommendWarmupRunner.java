package com.hmdp.geo;

import com.hmdp.dto.RecommendContext;
import com.hmdp.service.recommend.CuratedRecallService;
import com.hmdp.service.recommend.GeoRecallService;
import com.hmdp.service.recommend.HotRecallService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 推荐冷启动预热：提前触达 DB/GEO/统计查询，降低首次 Feed 的 50ms 召回超时概率。
 */
@Slf4j
@Component
@Order(4)
public class RecommendWarmupRunner implements ApplicationRunner {

    @Value("${hmdp.recommend.warmup-on-startup:true}")
    private boolean enabled;

    @Resource
    private HotRecallService hotRecallService;

    @Resource
    private GeoRecallService geoRecallService;

    @Resource
    private CuratedRecallService curatedRecallService;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("推荐召回预热已关闭");
            return;
        }
        RecommendContext context = new RecommendContext();
        context.setCity("杭州");
        context.setUserKey("warmup");
        context.setColdStart(true);
        long start = System.currentTimeMillis();
        try {
            hotRecallService.recall(context);
        } catch (Exception e) {
            log.warn("推荐 HOT 预热失败: {}", e.getMessage());
        }
        try {
            geoRecallService.recall(context);
        } catch (Exception e) {
            log.warn("推荐 GEO 预热失败: {}", e.getMessage());
        }
        try {
            curatedRecallService.recall(context);
        } catch (Exception e) {
            log.warn("推荐 CURATED 预热失败: {}", e.getMessage());
        }
        log.info("推荐召回预热完成，耗时 {} ms", System.currentTimeMillis() - start);
    }
}
