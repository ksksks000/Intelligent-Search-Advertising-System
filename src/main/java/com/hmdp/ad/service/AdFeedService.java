package com.hmdp.ad.service;

import com.hmdp.ad.dto.AdAuctionResponse;
import com.hmdp.ad.dto.AdSlotItem;
import com.hmdp.config.AdProperties;
import com.hmdp.dto.FeedItem;
import com.hmdp.entity.Activity;
import com.hmdp.service.IActivityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固定位置插入广告：第 3/6 位替换自然结果，保持总条数不变。
 * 选择固定位置而非合并排序：自然精排权重冻结，广告只占预留位，不反向影响自然排序。
 */
@Slf4j
@Service
public class AdFeedService {

    @Resource
    private AdProperties properties;
    @Resource
    private IActivityService activityService;

    public List<FeedItem> merge(List<FeedItem> natural, AdAuctionResponse auction, int pageCursor) {
        if (natural == null || auction == null || auction.getItems() == null
                || auction.getItems().isEmpty() || pageCursor != 0) {
            return natural;
        }
        int[] positions = {properties.getSlotPositionA(), properties.getSlotPositionB()};
        Map<Integer, AdSlotItem> adByPosition = new LinkedHashMap<>();
        Set<Long> adActivityIds = new HashSet<>();
        for (AdSlotItem ad : auction.getItems()) {
            int position = resolvePosition(ad.getSlotId(), positions, adByPosition.keySet());
            if (position > 0) {
                adByPosition.put(position, ad);
                if (ad.getActivityId() != null) {
                    adActivityIds.add(ad.getActivityId());
                }
            }
        }
        List<FeedItem> pool = new ArrayList<>(natural);
        pool.removeIf(item -> item.getActivityId() != null
                && adActivityIds.contains(item.getActivityId()));
        List<FeedItem> result = new ArrayList<>(natural.size());
        for (int position = 1; position <= natural.size(); position++) {
            AdSlotItem ad = adByPosition.get(position);
            if (ad != null) {
                result.add(toFeedItem(ad));
            } else if (!pool.isEmpty()) {
                result.add(pool.remove(0));
            }
        }
        log.info("ad feed merge natural={}, auctionItems={}, resultAds={}",
                natural.size(), auction.getItems().size(),
                result.stream().filter(item -> Boolean.TRUE.equals(item.getAd())).count());
        return result;
    }

    private int resolvePosition(String slotId, int[] positions, Set<Integer> used) {
        if (slotId != null && slotId.startsWith("feed-")) {
            try {
                int parsed = Integer.parseInt(slotId.substring("feed-".length()));
                for (int position : positions) {
                    if (position == parsed && !used.contains(position)) {
                        return position;
                    }
                }
            } catch (NumberFormatException ignored) {
                // 非法 slotId 走下一个预留位
            }
        }
        for (int position : positions) {
            if (!used.contains(position)) {
                return position;
            }
        }
        return -1;
    }

    private FeedItem toFeedItem(AdSlotItem ad) {        Activity activity = ad.getActivityId() == null ? null
                : activityService.getById(ad.getActivityId());
        FeedItem item = new FeedItem();
        item.setActivityId(ad.getActivityId());
        item.setTitle(activity != null && activity.getTitle() != null
                ? activity.getTitle() : ad.getTitle());
        item.setSubTitle(activity != null ? activity.getSubTitle() : ad.getDescription());
        item.setImages(activity != null && activity.getImages() != null
                ? activity.getImages() : ad.getImage());
        item.setActivityType(activity == null ? null : activity.getActivityType());
        item.setCategoryId(activity != null ? activity.getCategoryId() : ad.getCategoryId());
        item.setShopId(activity == null ? null : activity.getShopId());
        item.setCity(activity == null ? null : activity.getCity());
        item.setArea(activity == null ? null : activity.getArea());
        item.setPayValue(activity == null ? null : activity.getPayValue());
        item.setOriginalPrice(activity == null ? null : activity.getOriginalPrice());
        item.setQualityScore(activity == null ? null : activity.getQualityScore());
        item.setScore(ad.getEcpm() == null ? null : ad.getEcpm().doubleValue());
        List<String> sources = new ArrayList<>();
        sources.add("AD");
        item.setSources(sources);
        item.setAd(true);
        item.setAdId(ad.getAdId());
        item.setCampaignId(ad.getCampaignId());
        item.setSlotId(ad.getSlotId());
        item.setBillingId(ad.getBillingId());
        item.setRequestId(ad.getRequestId());
        item.setBid(ad.getBid());
        item.setPctr(ad.getPctr() == null ? null : ad.getPctr().doubleValue());
        item.setEcpm(ad.getEcpm() == null ? null : ad.getEcpm().doubleValue());
        return item;
    }
}
