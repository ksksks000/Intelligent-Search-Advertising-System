package com.hmdp.ad.service;

import com.hmdp.ad.dto.AdAuctionResponse;
import com.hmdp.ad.dto.AdSlotItem;
import com.hmdp.config.AdProperties;
import com.hmdp.dto.FeedItem;
import com.hmdp.service.IActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdFeedServiceTest {

    private AdFeedService service;

    @BeforeEach
    void setUp() {
        service = new AdFeedService();
        AdProperties properties = new AdProperties();
        properties.setSlotPositionA(3);
        properties.setSlotPositionB(6);
        IActivityService activityService = mock(IActivityService.class);
        when(activityService.getById(anyLong())).thenReturn(null);
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "activityService", activityService);
    }

    @Test
    @DisplayName("广告固定落在第 3/6 位，保持 10 条")
    void fixedSlots() {
        List<FeedItem> natural = natural(10);
        AdAuctionResponse auction = auction(
                ad(100L, 9000L, "feed-3"),
                ad(101L, 9001L, "feed-6"));
        List<FeedItem> merged = service.merge(natural, auction, 0);
        assertEquals(10, merged.size());
        assertEquals(100L, merged.get(2).getCampaignId());
        assertEquals(101L, merged.get(5).getCampaignId());
        assertTrue(merged.get(2).getAd());
        assertTrue(merged.get(5).getAd());
    }

    @Test
    @DisplayName("广告活动与自然结果重复时移除重复项且仍在预留位")
    void duplicateActivityStillReserved() {
        List<FeedItem> natural = natural(10);
        AdAuctionResponse auction = auction(ad(100L, 1L, "feed-3"));
        List<FeedItem> merged = service.merge(natural, auction, 0);
        assertEquals(10, merged.size());
        assertEquals(100L, merged.get(2).getCampaignId());
        long duplicateCount = merged.stream()
                .filter(item -> Long.valueOf(1L).equals(item.getActivityId())).count();
        assertEquals(1, duplicateCount);
    }

    private List<FeedItem> natural(int size) {
        List<FeedItem> list = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            FeedItem item = new FeedItem();
            item.setActivityId((long) i);
            item.setAd(false);
            list.add(item);
        }
        return list;
    }

    private AdAuctionResponse auction(AdSlotItem... items) {
        AdAuctionResponse response = new AdAuctionResponse();
        response.setItems(Arrays.asList(items));
        return response;
    }

    private AdSlotItem ad(Long campaignId, Long activityId, String slotId) {
        AdSlotItem item = new AdSlotItem();
        item.setAdId(campaignId);
        item.setCampaignId(campaignId);
        item.setActivityId(activityId);
        item.setSlotId(slotId);
        item.setTitle("ad-" + campaignId);
        item.setBid(10L);
        return item;
    }
}
