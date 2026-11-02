package com.hmdp.service.recommend;

import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.ActivityCandidate;
import com.hmdp.entity.Activity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RerankerTest {

    private Reranker reranker;

    @BeforeEach
    void setUp() {
        reranker = new Reranker();
        ReflectionTestUtils.setField(reranker, "properties", new RecommendProperties());
    }

    @Test
    @DisplayName("同一类目连续不超过 2 个")
    void categoryDiversity() {
        List<ActivityCandidate> input = Arrays.asList(
                candidate(1L, 1L, 1L), candidate(2L, 1L, 2L),
                candidate(3L, 1L, 3L), candidate(4L, 2L, 4L));
        List<Long> categories = reranker.rerank(input).stream()
                .map(c -> c.getActivity().getCategoryId()).collect(Collectors.toList());
        assertEquals(Arrays.asList(1L, 1L, 2L, 1L), categories);
    }

    @Test
    @DisplayName("同一商家连续不超过 1 个")
    void shopDiversity() {
        List<ActivityCandidate> input = Arrays.asList(
                candidate(1L, 1L, 10L), candidate(2L, 2L, 10L), candidate(3L, 3L, 20L));
        List<Long> shops = reranker.rerank(input).stream()
                .map(c -> c.getActivity().getShopId()).collect(Collectors.toList());
        assertEquals(Arrays.asList(10L, 20L, 10L), shops);
    }

    @Test
    @DisplayName("强单类目偏好：延后候选在约束允许时回填，Top10 不被其他类目挤占")
    void concentratedCategoryStillFillsTop() {
        List<ActivityCandidate> input = Arrays.asList(
                candidate(1L, 1L, 1L), candidate(2L, 1L, 2L), candidate(3L, 1L, 3L),
                candidate(4L, 1L, 4L), candidate(5L, 1L, 5L), candidate(6L, 1L, 6L),
                candidate(7L, 2L, 7L));
        List<ActivityCandidate> output = reranker.rerank(input);
        long mainCount = output.stream().limit(10)
                .filter(c -> c.getActivity().getCategoryId().equals(1L))
                .count();
        assertEquals(6L, mainCount);
    }

    private ActivityCandidate candidate(Long id, Long categoryId, Long shopId) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setCategoryId(categoryId);
        activity.setShopId(shopId);
        ActivityCandidate candidate = new ActivityCandidate();
        candidate.setActivityId(id);
        candidate.setActivity(activity);
        return candidate;
    }
}
