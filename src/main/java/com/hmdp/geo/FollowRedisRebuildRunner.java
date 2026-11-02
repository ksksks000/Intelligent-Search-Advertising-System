package com.hmdp.geo;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 启动时从 tb_follow 分页重建 follows:{userId}。
 * Redis 被清空后重启可恢复；幂等 SADD；失败只记录日志，不阻塞启动。
 */
@Slf4j
@Component
@Order(3)
public class FollowRedisRebuildRunner implements ApplicationRunner {

    private static final int PAGE_SIZE = 500;

    @Resource
    private FollowMapper followMapper;

    @Resource
    private StringRedisTemplate redis;

    @Value("${hmdp.follow.rebuild-on-startup:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("follows 启动重建已关闭（hmdp.follow.rebuild-on-startup=false）");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> stats = rebuild();
            log.info("follows 启动重建完成，耗时 {} ms，{}", System.currentTimeMillis() - start, stats);
        } catch (Exception e) {
            log.error("follows 启动重建失败，应用继续启动；可调用 POST /admin/recommend/follows/rebuild 重试", e);
        }
    }

    /**
     * 分页读取关注关系，按 userId 分组批量 SADD。返回统计信息。
     */
    public Map<String, Object> rebuild() {
        int pageNo = 1;
        long relationCount = 0;
        long userCount = 0;
        while (true) {
            Page<Follow> page = followMapper.selectPage(
                    new Page<>(pageNo, PAGE_SIZE),
                    Wrappers.<Follow>lambdaQuery().orderByAsc(Follow::getId));
            List<Follow> records = page.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            Map<Long, List<Long>> byUser = records.stream()
                    .filter(f -> f.getUserId() != null && f.getFollowUserId() != null)
                    .collect(Collectors.groupingBy(Follow::getUserId,
                            Collectors.mapping(Follow::getFollowUserId, Collectors.toList())));
            for (Map.Entry<Long, List<Long>> entry : byUser.entrySet()) {
                try {
                    String[] values = entry.getValue().stream()
                            .map(String::valueOf).toArray(String[]::new);
                    redis.opsForSet().add(RedisConstants.FOLLOW_KEY + entry.getKey(), values);
                    relationCount += values.length;
                    userCount++;
                } catch (Exception e) {
                    log.warn("重建 follows:{} 失败，跳过该用户: {}", entry.getKey(), e.getMessage());
                }
            }
            if (records.size() < PAGE_SIZE) {
                break;
            }
            pageNo++;
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pages", pageNo);
        stats.put("relations", relationCount);
        stats.put("users", userCount);
        return stats;
    }
}
