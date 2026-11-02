package com.hmdp.geo;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 前置修复 1：Redis 清空后重启，follows:{userId} 能从 tb_follow 恢复。
 */
@DataRedisTest
@ContextConfiguration(classes = FollowRedisRebuildRunnerTest.TestConfig.class)
class FollowRedisRebuildRunnerTest {

    @Configuration
    @AutoConfigurationPackage
    static class TestConfig {
    }

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanUp() {
        redis.delete(Arrays.asList("follows:9001", "follows:9002"));
    }

    @Test
    @DisplayName("Redis 清空后，分页读取 tb_follow 重建 follows:*")
    void rebuildFromDb() {
        FollowMapper mapper = mock(FollowMapper.class);
        Page<Follow> page1 = new Page<>(1, 500);
        page1.setRecords(Arrays.asList(
                new Follow().setUserId(9001L).setFollowUserId(9002L),
                new Follow().setUserId(9001L).setFollowUserId(9003L),
                new Follow().setUserId(9002L).setFollowUserId(9003L)));
        Page<Follow> page2 = new Page<>(2, 500);
        page2.setRecords(Collections.emptyList());
        when(mapper.selectPage(any(), any())).thenReturn(page1, page2);

        redis.delete(Arrays.asList("follows:9001", "follows:9002"));
        FollowRedisRebuildRunner runner = new FollowRedisRebuildRunner();
        ReflectionTestUtils.setField(runner, "followMapper", mapper);
        ReflectionTestUtils.setField(runner, "redis", redis);
        ReflectionTestUtils.setField(runner, "enabled", true);

        Map<String, Object> stats = runner.rebuild();

        Set<String> first = redis.opsForSet().members("follows:9001");
        Set<String> second = redis.opsForSet().members("follows:9002");
        assertEquals(2, first.size());
        assertTrue(first.containsAll(Arrays.asList("9002", "9003")));
        assertEquals(Collections.singleton("9003"), second);
        assertEquals(2L, ((Number) stats.get("users")).longValue());
        assertEquals(3L, ((Number) stats.get("relations")).longValue());
    }
}
