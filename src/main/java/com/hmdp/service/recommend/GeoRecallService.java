package com.hmdp.service.recommend;

import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.ActivityCandidate;
import com.hmdp.dto.RecommendContext;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 地理召回：act:geo:{city} 城市分片，按距离排序。
 */
@Slf4j
@Service
public class GeoRecallService implements RecallService {

    private static final Map<String, Point> CITY_CENTERS = new HashMap<>();

    static {
        CITY_CENTERS.put("杭州", new Point(120.1551, 30.2741));
        CITY_CENTERS.put("上海", new Point(121.4737, 31.2304));
        CITY_CENTERS.put("北京", new Point(116.4074, 39.9042));
        CITY_CENTERS.put("深圳", new Point(114.0579, 22.5431));
        CITY_CENTERS.put("成都", new Point(104.0665, 30.5723));
    }

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private RecommendProperties properties;

    @Override
    public String source() {
        return "GEO";
    }

    @Override
    public List<ActivityCandidate> recall(RecommendContext context) {
        String city = context.getCity() == null || context.getCity().trim().isEmpty()
                ? "杭州" : context.getCity().trim();
        Point center = context.getX() != null && context.getY() != null
                ? new Point(context.getX(), context.getY())
                : CITY_CENTERS.getOrDefault(city, CITY_CENTERS.get("杭州"));
        double radiusKm = Math.max(1D, properties.getGeoRadiusKm());
        int limit = Math.max(1, properties.getGeoLimit());
        try {
            RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs
                    .newGeoRadiusArgs().includeDistance().limit(limit).sortAscending();
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo()
                    .radius(RedisConstants.ACTIVITY_GEO_KEY_PREFIX + city,
                            new Circle(center, new Distance(radiusKm, Metrics.KILOMETERS)), args);
            if (results == null || results.getContent() == null) {
                return new ArrayList<>();
            }
            List<ActivityCandidate> candidates = new ArrayList<>();
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
                Long id = parseLong(result.getContent().getName());
                if (id == null) {
                    continue;
                }
                double distanceKm = result.getDistance() == null
                        ? radiusKm : result.getDistance().getValue();
                double score = Math.max(0D, 1D - Math.min(1D, distanceKm / radiusKm));
                candidates.add(ActivityCandidate.of(id, source(), score));
            }
            return candidates;
        } catch (Exception e) {
            log.warn("DEGRADED: geo recall failed, city={}, error={}", city, e.getMessage());
            return new ArrayList<>();
        }
    }

    private Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
