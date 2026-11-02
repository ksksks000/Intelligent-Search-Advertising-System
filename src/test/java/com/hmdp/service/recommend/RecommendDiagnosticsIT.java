package com.hmdp.service.recommend;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.ActivityCandidate;
import com.hmdp.dto.RecommendContext;
import com.hmdp.dto.UserProfile;
import com.hmdp.entity.Activity;
import com.hmdp.entity.BehaviorEvent;
import com.hmdp.mapper.BehaviorEventMapper;
import com.hmdp.service.BehaviorEventService;
import com.hmdp.service.IActivityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 阶段 4 前置修复 A-2 第一步：只读诊断，不改业务代码。
 * 直接调用真实召回/粗排/精排，打印每路候选量、TAG 分数分布、粗排 Top100 来源构成、精排 Top10 构成。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RecommendDiagnosticsIT {

    @Autowired
    private List<RecallService> recallServices;

    @Autowired
    private CoarseRanker coarseRanker;

    @Autowired
    private FineRanker fineRanker;

    @Autowired
    private BehaviorEventService behaviorEventService;

    @Autowired
    private IActivityService activityService;

    @Autowired
    private BehaviorEventMapper behaviorEventMapper;

    @Autowired
    private InterestVectorService interestVectorService;

    private final List<String> testDevices = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (!testDevices.isEmpty()) {
            behaviorEventMapper.delete(new QueryWrapper<BehaviorEvent>()
                    .in("device_id", testDevices));
        }
        testDevices.clear();
    }

    @Test
    @DisplayName("VECTOR 覆盖：向量召回有而 TAG 召回没有的活动")
    void vectorCoverage() {
        coverageScenario("hotpot", Arrays.asList(900002L, 900005L, 900009L, 900020L, 910001L));
        coverageScenario("cross", Arrays.asList(900002L, 900010L, 900018L, 910004L, 910008L));
    }

    private void coverageScenario(String name, List<Long> activityIds) {
        String deviceId = "diag-vec-" + name + "-" + UUID.randomUUID().toString().substring(0, 8);
        testDevices.add(deviceId);
        for (Long activityId : activityIds) {
            behaviorEventService.save(new BehaviorEvent()
                    .setDeviceId(deviceId)
                    .setEventType("click")
                    .setTargetType("activity")
                    .setTargetId(activityId)
                    .setScene("feed")
                    .setEventTime(LocalDateTime.now()));
        }
        UserProfile profile = behaviorEventService.buildProfile(null, deviceId, 7, 50);
        RecommendContext context = new RecommendContext();
        context.setDeviceId(deviceId);
        context.setUserKey("d" + deviceId);
        context.setCity("杭州");
        context.setColdStart(false);
        context.setProfile(profile);
        interestVectorService.buildAndCache(profile, context.getUserKey());

        List<ActivityCandidate> tag = Collections.emptyList();
        List<ActivityCandidate> vector = Collections.emptyList();
        for (RecallService service : recallServices) {
            if ("TAG".equals(service.source())) {
                tag = service.recall(context);
            } else if ("VECTOR".equals(service.source())) {
                vector = service.recall(context);
            }
        }
        if (tag == null) {
            tag = Collections.emptyList();
        }
        if (vector == null) {
            vector = Collections.emptyList();
        }
        Set<Long> tagIds = tag.stream().map(ActivityCandidate::getActivityId).collect(Collectors.toSet());
        List<ActivityCandidate> vectorOnly = vector.stream()
                .filter(c -> !tagIds.contains(c.getActivityId()))
                .sorted(Comparator.comparing(ActivityCandidate::getRecallScore).reversed())
                .collect(Collectors.toList());
        Map<Long, Activity> activities = activityService.listByIds(
                        vectorOnly.stream().map(ActivityCandidate::getActivityId)
                                .collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(Activity::getId, a -> a));

        System.out.println("\n======== VECTOR COVERAGE " + name + " ========");
        System.out.println("TAG=" + tag.size() + ", VECTOR=" + vector.size()
                + ", VECTOR_ONLY(not in TAG)=" + vectorOnly.size()
                + ", VECTOR.es=" + context.getStageCosts().get("recall.VECTOR.es") + "ms"
                + ", embedding=" + context.getStageCosts().get("recall.VECTOR.embedding") + "ms");
        System.out.println("VECTOR top10 (TAG hit?):");
        for (int i = 0; i < Math.min(10, vector.size()); i++) {
            ActivityCandidate c = vector.get(i);
            System.out.printf("  %d id=%s tagHit=%s score=%.3f%n",
                    i + 1, c.getActivityId(), tagIds.contains(c.getActivityId()), c.getRecallScore());
        }
        System.out.println("VECTOR_ONLY top10:");
        for (int i = 0; i < Math.min(10, vectorOnly.size()); i++) {
            ActivityCandidate c = vectorOnly.get(i);
            Activity a = activities.get(c.getActivityId());
            System.out.printf("  %d id=%s cat=%s score=%.3f title=%s%n",
                    i + 1, c.getActivityId(), a == null ? null : a.getCategoryId(),
                    c.getRecallScore(), a == null ? null : a.getTitle());
        }
        assertFalse(vector.isEmpty());
    }

    @Test
    @DisplayName("诊断：火锅/KTV/美容三场景的召回与粗排构成")
    void diagnose() {
        diagnoseScenario("hotpot", 1L, Arrays.asList(900002L, 900005L, 900009L, 900020L, 910001L));
        diagnoseScenario("ktv", 2L, Arrays.asList(900010L, 900011L, 900012L, 900014L, 910002L));
        diagnoseScenario("beauty", 6L, Arrays.asList(910006L, 910016L, 910026L, 910036L, 910046L));
    }

    private void diagnoseScenario(String name, Long mainCategory, List<Long> activityIds) {
        String deviceId = "diag-" + name + "-" + UUID.randomUUID().toString().substring(0, 8);
        testDevices.add(deviceId);
        for (Long activityId : activityIds) {
            behaviorEventService.save(new BehaviorEvent()
                    .setDeviceId(deviceId)
                    .setEventType("click")
                    .setTargetType("activity")
                    .setTargetId(activityId)
                    .setScene("feed")
                    .setEventTime(LocalDateTime.now()));
        }
        UserProfile profile = behaviorEventService.buildProfile(null, deviceId, 7, 50);
        RecommendContext context = new RecommendContext();
        context.setDeviceId(deviceId);
        context.setUserKey("d" + deviceId);
        context.setCity("杭州");
        context.setColdStart(false);
        context.setProfile(profile);

        System.out.println("\n================ DIAG " + name + " mainCat=" + mainCategory + " ================");
        System.out.println("profile totalClicks=" + profile.getTotalClicks()
                + ", categoryClicks=" + profile.getCategoryClickCounts()
                + ", tags=" + profile.getTagWeights());

        Map<Long, Long> categoryMap = loadCategoryMap(activityIds);
        List<ActivityCandidate> all = new ArrayList<>();
        for (RecallService service : recallServices) {
            List<ActivityCandidate> candidates;
            try {
                candidates = service.recall(context);
            } catch (Exception e) {
                System.out.println("recall=" + service.source() + " EXCEPTION " + e.getMessage());
                continue;
            }
            if (candidates == null) {
                candidates = Collections.emptyList();
            }
            all.addAll(candidates);
            int mainCount = countMain(candidates, mainCategory, categoryMap);
            System.out.printf("recall=%s total=%d main=%d%n",
                    service.source(), candidates.size(), mainCount);
            if ("TAG".equals(service.source()) && mainCount > 0) {
                List<Double> scores = candidates.stream()
                        .filter(c -> categoryMap.getOrDefault(c.getActivityId(), -1L).equals(mainCategory))
                        .map(ActivityCandidate::getRecallScore)
                        .sorted().collect(Collectors.toList());
                System.out.printf("TAG main score min=%.3f median=%.3f max=%.3f%n",
                        scores.get(0), scores.get(scores.size() / 2), scores.get(scores.size() - 1));
            }
        }

        List<ActivityCandidate> coarse = coarseRanker.rank(all, context);
        Map<Long, Long> coarseCategory = new LinkedHashMap<>();
        for (ActivityCandidate c : coarse) {
            if (c.getActivity() != null && c.getActivity().getCategoryId() != null) {
                coarseCategory.put(c.getActivityId(), c.getActivity().getCategoryId());
            }
        }
        System.out.println("coarse size=" + coarse.size());
        for (String source : Arrays.asList("FOLLOW", "HOT", "GEO", "TAG", "VECTOR", "CURATED")) {
            int total = 0;
            int main = 0;
            for (ActivityCandidate c : coarse) {
                if (c.getSourceScores().containsKey(source)) {
                    total++;
                    if (mainCategory.equals(coarseCategory.get(c.getActivityId()))) {
                        main++;
                    }
                }
            }
            System.out.printf("coarse source=%s total=%d main=%d%n", source, total, main);
        }
        System.out.println("coarse TAG top5:");
        coarse.stream()
                .filter(c -> c.getSourceScores().containsKey("TAG"))
                .sorted(Comparator.comparingDouble(ActivityCandidate::getCoarseScore).reversed())
                .limit(5)
                .forEach(c -> System.out.printf("  id=%s coarse=%.3f sources=%s%n",
                        c.getActivityId(), c.getCoarseScore(), c.getSourceScores()));

        List<ActivityCandidate> fine = fineRanker.rank(coarse, context);
        System.out.println("fine top10:");
        for (int i = 0; i < Math.min(10, fine.size()); i++) {
            ActivityCandidate c = fine.get(i);
            Activity a = c.getActivity();
            System.out.printf("  %d id=%s cat=%s sources=%s score=%.3f title=%s%n",
                    i + 1, c.getActivityId(), a == null ? null : a.getCategoryId(),
                    c.getSourceScores().keySet(), c.getFineScore(),
                    a == null ? null : a.getTitle());
        }
        assertFalse(fine.isEmpty());
    }

    private int countMain(List<ActivityCandidate> candidates, Long mainCategory,
                          Map<Long, Long> categoryMap) {
        int count = 0;
        for (ActivityCandidate candidate : candidates) {
            if (mainCategory.equals(categoryMap.get(candidate.getActivityId()))) {
                count++;
            }
        }
        return count;
    }

    private Map<Long, Long> loadCategoryMap(List<Long> seedIds) {
        List<Long> ids = new ArrayList<>(seedIds);
        // 召回候选的类目用全量在线活动映射，避免逐个召回查 DB
        List<Activity> activities = activityService.lambdaQuery()
                .select(Activity::getId, Activity::getCategoryId)
                .eq(Activity::getStatus, Activity.STATUS_ONLINE)
                .list();
        return activities.stream()
                .filter(a -> a.getId() != null && a.getCategoryId() != null)
                .collect(Collectors.toMap(Activity::getId, Activity::getCategoryId, (a, b) -> a));
    }
}
