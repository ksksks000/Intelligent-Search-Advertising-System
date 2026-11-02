package com.hmdp.service.recommend;

import com.hmdp.config.RecommendProperties;
import com.hmdp.dto.ActivityCandidate;
import com.hmdp.entity.Activity;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 重排：同一 categoryId 连续不超过 N，同一 shopId 连续不超过 M。
 * <p>
 * 关键：被延后的候选不是简单追加到末尾，而是在后续位置满足约束时优先回填；
 * 否则“全 cat1/cat2 的强偏好用户”会被打散到第 10 位之后，Top10 反而丢失主类目。
 */
@Service
public class Reranker {

    @Resource
    private RecommendProperties properties;

    public List<ActivityCandidate> rerank(List<ActivityCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        int categoryMax = Math.max(1, properties.getCategoryMaxConsecutive());
        int shopMax = Math.max(0, properties.getShopMaxConsecutive());
        List<ActivityCandidate> result = new ArrayList<>(candidates.size());
        Deque<ActivityCandidate> deferred = new ArrayDeque<>();
        List<Long> recentCategories = new ArrayList<>();
        Long recentShop = null;
        int index = 0;

        while (result.size() < candidates.size()) {
            boolean added = false;

            // 1) 优先回填最早一个满足约束的延后候选
            Iterator<ActivityCandidate> iterator = deferred.iterator();
            while (iterator.hasNext()) {
                ActivityCandidate candidate = iterator.next();
                Activity activity = candidate.getActivity();
                if (activity == null) {
                    iterator.remove();
                    continue;
                }
                if (fits(activity, recentCategories, recentShop, categoryMax, shopMax)) {
                    iterator.remove();
                    result.add(candidate);
                    updateCategory(recentCategories, activity.getCategoryId(), categoryMax);
                    recentShop = activity.getShopId();
                    added = true;
                    break;
                }
            }
            if (added) {
                continue;
            }

            // 2) 从精排序列取下一个满足约束的候选；不满足的先延后
            while (index < candidates.size()) {
                ActivityCandidate candidate = candidates.get(index++);
                Activity activity = candidate.getActivity();
                if (activity == null) {
                    continue;
                }
                if (fits(activity, recentCategories, recentShop, categoryMax, shopMax)) {
                    result.add(candidate);
                    updateCategory(recentCategories, activity.getCategoryId(), categoryMax);
                    recentShop = activity.getShopId();
                    added = true;
                    break;
                }
                deferred.add(candidate);
            }
            if (added) {
                continue;
            }

            // 3) 没有可回填/可取的候选（极端情况）：强制取队首，保证不丢候选
            ActivityCandidate forced = deferred.poll();
            if (forced == null) {
                break;
            }
            result.add(forced);
            Activity activity = forced.getActivity();
            if (activity != null) {
                updateCategory(recentCategories, activity.getCategoryId(), categoryMax);
                recentShop = activity.getShopId();
            }
        }
        return result;
    }

    private boolean fits(Activity activity, List<Long> recentCategories, Long recentShop,
                         int categoryMax, int shopMax) {
        Long categoryId = activity.getCategoryId();
        if (categoryId != null && violatesCategory(recentCategories, categoryId, categoryMax)) {
            return false;
        }
        Long shopId = activity.getShopId();
        return shopMax <= 0 || shopId == null || !shopId.equals(recentShop);
    }

    private void updateCategory(List<Long> recentCategories, Long categoryId, int max) {
        if (categoryId == null) {
            recentCategories.clear();
            return;
        }
        recentCategories.add(categoryId);
        while (recentCategories.size() > max) {
            recentCategories.remove(0);
        }
    }

    private boolean violatesCategory(List<Long> recentCategories, Long categoryId, int max) {
        if (categoryId == null || recentCategories.size() < max) {
            return false;
        }
        for (int i = recentCategories.size() - max; i < recentCategories.size(); i++) {
            if (!categoryId.equals(recentCategories.get(i))) {
                return false;
            }
        }
        return true;
    }
}
