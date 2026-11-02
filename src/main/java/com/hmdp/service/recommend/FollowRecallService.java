package com.hmdp.service.recommend;

import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.ActivityCandidate;
import com.hmdp.dto.RecommendContext;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 关注召回：follows:{userId} + feed:{userId}（blogId）-> tb_blog.activity_id。
 * feed 为空时回退 DB 查询被关注用户的近期 blog。
 */
@Slf4j
@Service
public class FollowRecallService implements RecallService {

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private IFollowService followService;

    @Resource
    private IBlogService blogService;

    @Resource
    private RecommendProperties properties;

    @Override
    public String source() {
        return "FOLLOW";
    }

    @Override
    public List<ActivityCandidate> recall(RecommendContext context) {
        Long userId = context.getUserId();
        if (userId == null) {
            return new ArrayList<>();
        }
        int limit = Math.max(1, properties.getFollowLimit());
        Set<String> followIds = loadFollowIds(userId);
        if (followIds.isEmpty()) {
            return new ArrayList<>();
        }

        List<Blog> blogs = loadBlogs(userId, followIds, limit);
        if (blogs.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, Double> scores = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (Blog blog : blogs) {
            if (blog.getActivityId() == null) {
                continue;
            }
            double recency = recency(blog.getCreateTime(), now);
            scores.merge(blog.getActivityId(), 0.5D + 0.5D * recency, Math::max);
        }
        List<ActivityCandidate> candidates = new ArrayList<>(scores.size());
        for (Map.Entry<Long, Double> entry : scores.entrySet()) {
            candidates.add(ActivityCandidate.of(entry.getKey(), source(), entry.getValue()));
        }
        return candidates;
    }

    private Set<String> loadFollowIds(Long userId) {
        Set<String> followIds = redis.opsForSet().members(RedisConstants.FOLLOW_KEY + userId);
        if (followIds != null && !followIds.isEmpty()) {
            return followIds;
        }
        List<Follow> follows = followService.lambdaQuery()
                .eq(Follow::getUserId, userId).list();
        if (follows == null || follows.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        String[] ids = follows.stream()
                .map(f -> String.valueOf(f.getFollowUserId()))
                .toArray(String[]::new);
        try {
            redis.opsForSet().add(RedisConstants.FOLLOW_KEY + userId, ids);
        } catch (Exception e) {
            log.warn("回填 follows:{} 失败: {}", userId, e.getMessage());
        }
        return java.util.Arrays.stream(ids).collect(Collectors.toSet());
    }

    private List<Blog> loadBlogs(Long userId, Set<String> followIds, int limit) {
        Set<String> feedBlogIds = redis.opsForZSet()
                .reverseRange(RedisConstants.FEED_KEY + userId, 0, limit - 1);
        List<Long> feedIds = feedBlogIds == null ? new ArrayList<>()
                : feedBlogIds.stream().map(Long::valueOf).collect(Collectors.toList());
        List<Long> followUserIds = followIds.stream().map(Long::valueOf).collect(Collectors.toList());
        if (feedIds.isEmpty() && followUserIds.isEmpty()) {
            return new ArrayList<>();
        }
        // 一次查询覆盖 follows 与 feed 两个来源，减少 50ms 召回预算内的 DB 往返
        List<Blog> blogs = blogService.lambdaQuery()
                .isNotNull(Blog::getActivityId)
                .eq(Blog::getStatus, 1)
                .and(w -> {
                    boolean first = true;
                    if (!feedIds.isEmpty()) {
                        w.in(Blog::getId, feedIds);
                        first = false;
                    }
                    if (!followUserIds.isEmpty()) {
                        if (!first) {
                            w.or();
                        }
                        w.in(Blog::getUserId, followUserIds);
                    }
                })
                .orderByDesc(Blog::getCreateTime)
                .last("LIMIT " + limit)
                .list();
        return blogs == null ? new ArrayList<>() : blogs;
    }

    private double recency(LocalDateTime time, LocalDateTime now) {
        if (time == null) {
            return 0.5D;
        }
        long days = Math.abs(ChronoUnit.DAYS.between(time, now));
        return 1D / (1D + days / 7D);
    }
}
