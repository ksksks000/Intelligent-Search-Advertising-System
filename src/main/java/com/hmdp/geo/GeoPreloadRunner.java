package com.hmdp.geo;

import com.hmdp.service.IActivityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 启动时重建地理索引。
 * 原仓库 shop:geo:{typeId} 只在单测里写入，线上 GEO 查询实际为空；
 * 这里在启动后按 DB 全量重建 shop GEO 与活动 GEO，失败不阻塞启动。
 */
@Slf4j
@Component
public class GeoPreloadRunner implements ApplicationRunner {

    @Value("${hmdp.geo.preload-on-startup:true}")
    private boolean enabled;

    @Resource
    private IActivityService activityService;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("GEO 启动预热已关闭（hmdp.geo.preload-on-startup=false）");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> result = activityService.rebuildGeo();
            log.info("GEO 启动预热完成，耗时 {} ms，{}", System.currentTimeMillis() - start, result);
        } catch (Exception e) {
            log.error("GEO 启动预热失败，应用继续启动；可调用 POST /admin/geo/rebuild 重试", e);
        }
    }
}
