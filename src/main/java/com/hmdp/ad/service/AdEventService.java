package com.hmdp.ad.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ad.dto.AdBillingEvent;
import com.hmdp.ad.dto.AdEventRequest;
import com.hmdp.ad.dto.AdSlotItem;
import com.hmdp.ad.entity.AdExposureLog;
import com.hmdp.config.AdProperties;
import com.hmdp.entity.BehaviorEvent;
import com.hmdp.mapper.AdExposureLogMapper;
import com.hmdp.service.BehaviorEventService;
import com.hmdp.utils.RecommendUtils;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 广告曝光/点击/转化埋点、CTR 反馈与延迟转化等待队列。
 */
@Slf4j
@Service
public class AdEventService {

    public static final String EVENT_IMPRESSION = "impression";
    public static final String EVENT_CLICK = "click";
    public static final String EVENT_CONVERSION = "conversion";

    @Resource
    private AdExposureLogMapper exposureLogMapper;
    @Resource
    private AdBillingPublisher billingPublisher;
    @Resource
    private AdCtrStatsService ctrStatsService;
    @Resource
    private BehaviorEventService behaviorEventService;
    @Resource
    private StringRedisTemplate redis;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private AdProperties properties;

    public void recordImpression(AdSlotItem item, String requestId, String userKey,
                                 Long userId, String deviceId, String ip, String city) {
        if (item == null || item.getCampaignId() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        AdExposureLog logRow = new AdExposureLog()
                .setRequestId(requestId)
                .setAdId(item.getAdId())
                .setCampaignId(item.getCampaignId())
                .setUserKey(userKey)
                .setDeviceId(deviceId)
                .setIp(ip)
                .setSlotId(item.getSlotId())
                .setCity(city)
                .setBid(item.getBid())
                .setPctr(item.getPctr() == null ? BigDecimal.ZERO : item.getPctr())
                .setEcpm(item.getEcpm() == null ? BigDecimal.ZERO : item.getEcpm())
                .setExposedAt(LocalDateTime.now())
                .setClicked(0).setConverted(0)
                .setConversionStatus(AdExposureLog.CONVERSION_PENDING);
        try {
            exposureLogMapper.insert(logRow);
        } catch (Exception e) {
            log.error("写广告曝光明细失败 campaignId={}", item.getCampaignId(), e);
        }
        Map<String, Object> ext = ext(item, requestId);
        saveBehavior(userId, deviceId, item.getAdId(), EVENT_IMPRESSION, ext);
        ctrStatsService.recordImpression(item.getCampaignId(), item.getCategoryId());
    }

    public void recordClick(AdEventRequest request, Long userId, String deviceId, String ip) {
        if (request == null || (request.getAdId() == null && request.getCampaignId() == null)) {
            return;
        }
        String userKey = RecommendUtils.userKey(userId, deviceId);
        String clickId = request.getBillingId() != null ? request.getBillingId()
                : (request.getClickId() != null ? request.getClickId()
                : "click-" + System.currentTimeMillis());
        updateExposureClicked(request, clickId);
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("adId", request.getAdId());
        ext.put("campaignId", request.getCampaignId());
        ext.put("slotId", request.getSlotId());
        ext.put("clickId", clickId);
        saveBehavior(userId, deviceId, request.getAdId(), EVENT_CLICK, ext);
        ctrStatsService.recordClick(request.getCampaignId(), categoryOf(request));

        AdBillingEvent billing = new AdBillingEvent();
        billing.setBillingId(clickId + ":click");
        billing.setRequestId(request.getRequestId());
        billing.setAdId(request.getAdId());
        billing.setCampaignId(request.getCampaignId());
        billing.setUserKey(userKey);
        billing.setDeviceId(deviceId);
        billing.setIp(ip);
        billing.setEventType(EVENT_CLICK);
        billing.setSlotId(request.getSlotId());
        billing.setBid(0L);
        billing.setCost(0L);
        billingPublisher.publish(billing);

        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("clickId", clickId);
            body.put("adId", String.valueOf(request.getAdId()));
            body.put("campaignId", String.valueOf(request.getCampaignId()));
            body.put("userKey", userKey);
            body.put("slotId", request.getSlotId() == null ? "" : request.getSlotId());
            body.put("clickTime", String.valueOf(System.currentTimeMillis()));
            redis.opsForStream().add(StreamRecords.mapBacked(body)
                    .withStreamKey(RedisConstants.AD_CONVERSION_STREAM_KEY));
            redis.opsForZSet().add(RedisConstants.AD_CONVERSION_INDEX_KEY, clickId,
                    System.currentTimeMillis());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("clickId", clickId);
            meta.put("requestId", request.getRequestId());
            meta.put("adId", request.getAdId());
            meta.put("campaignId", request.getCampaignId());
            meta.put("userKey", userKey);
            meta.put("slotId", request.getSlotId());
            redis.opsForValue().set(RedisConstants.AD_CONVERSION_META_KEY + clickId,
                    objectMapper.writeValueAsString(meta),
                    properties.getConversionWindowSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("写广告转化等待队列失败 clickId={}", clickId, e);
        }
    }

    public boolean recordConversion(AdEventRequest request, Long userId, String deviceId) {
        String clickId = request.getClickId() != null ? request.getClickId() : request.getBillingId();
        if (clickId == null) {
            return false;
        }
        String metaJson = redis.opsForValue().get(RedisConstants.AD_CONVERSION_META_KEY + clickId);
        if (metaJson == null) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = objectMapper.readValue(metaJson, Map.class);
            String requestId = String.valueOf(meta.get("requestId"));
            Long adId = asLong(meta.get("adId"));
            Long campaignId = asLong(meta.get("campaignId"));
            String userKey = String.valueOf(meta.get("userKey"));
            AdExposureLog update = new AdExposureLog()
                    .setConverted(1)
                    .setConversionTime(LocalDateTime.now())
                    .setConversionStatus(AdExposureLog.CONVERSION_DONE);
            exposureLogMapper.update(update, new UpdateWrapper<AdExposureLog>()
                    .eq("request_id", requestId).eq("ad_id", adId));
            redis.opsForValue().set(RedisConstants.AD_CONVERSION_DONE_KEY + clickId,
                    request.getOrderId() == null ? "1" : request.getOrderId(),
                    properties.getConversionWindowSeconds(), TimeUnit.SECONDS);
            redis.opsForZSet().remove(RedisConstants.AD_CONVERSION_INDEX_KEY, clickId);
            Map<String, Object> ext = new LinkedHashMap<>();
            ext.put("adId", adId);
            ext.put("campaignId", campaignId);
            ext.put("clickId", clickId);
            ext.put("orderId", request.getOrderId());
            saveBehavior(userId, deviceId, adId, EVENT_CONVERSION, ext);
            AdBillingEvent billing = new AdBillingEvent();
            billing.setBillingId(clickId + ":conversion");
            billing.setRequestId(requestId);
            billing.setAdId(adId);
            billing.setCampaignId(campaignId);
            billing.setUserKey(userKey);
            billing.setEventType(EVENT_CONVERSION);
            billing.setBid(0L);
            billing.setCost(0L);
            billingPublisher.publish(billing);
            return true;
        } catch (Exception e) {
            log.error("回填广告转化失败 clickId={}", clickId, e);
            return false;
        }
    }

    /**
     * 扫描超过窗口仍未转化的点击，标记 TIMEOUT；不阻塞竞价链路。
     */
    @Scheduled(fixedDelayString = "${hmdp.ad.conversion-scan-ms:60000}")
    public void scanTimeout() {
        long deadline = System.currentTimeMillis() - properties.getConversionWindowSeconds() * 1000L;
        Set<String> clickIds = redis.opsForZSet().rangeByScore(
                RedisConstants.AD_CONVERSION_INDEX_KEY, 0, deadline);
        if (clickIds == null || clickIds.isEmpty()) {
            return;
        }
        for (String clickId : clickIds) {
            try {
                String done = redis.opsForValue().get(RedisConstants.AD_CONVERSION_DONE_KEY + clickId);
                if (done == null) {
                    String metaJson = redis.opsForValue().get(
                            RedisConstants.AD_CONVERSION_META_KEY + clickId);
                    if (metaJson != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> meta = objectMapper.readValue(metaJson, Map.class);
                        exposureLogMapper.update(new AdExposureLog()
                                        .setConversionStatus(AdExposureLog.CONVERSION_TIMEOUT),
                                new UpdateWrapper<AdExposureLog>()
                                        .eq("request_id", String.valueOf(meta.get("requestId")))
                                        .eq("ad_id", asLong(meta.get("adId"))));
                    }
                }
                redis.opsForZSet().remove(RedisConstants.AD_CONVERSION_INDEX_KEY, clickId);
            } catch (Exception e) {
                log.warn("扫描广告转化超时失败 clickId={}, error={}", clickId, e.getMessage());
            }
        }
    }

    private void updateExposureClicked(AdEventRequest request, String clickId) {
        try {
            exposureLogMapper.update(new AdExposureLog()
                            .setClicked(1).setClickTime(LocalDateTime.now()),
                    new UpdateWrapper<AdExposureLog>()
                            .eq("ad_id", request.getAdId())
                            .eq("campaign_id", request.getCampaignId())
                            .eq("request_id", request.getRequestId() == null
                                    ? "" : request.getRequestId())
                            .last("LIMIT 1"));
        } catch (Exception e) {
            log.warn("更新广告点击状态失败 clickId={}, error={}", clickId, e.getMessage());
        }
    }

    private void saveBehavior(Long userId, String deviceId, Long adId,
                              String eventType, Map<String, Object> ext) {
        try {
            BehaviorEvent event = new BehaviorEvent()
                    .setUserId(userId)
                    .setDeviceId(deviceId)
                    .setEventType(eventType)
                    .setTargetType("ad")
                    .setTargetId(adId)
                    .setScene("ad")
                    .setEventTime(LocalDateTime.now())
                    .setExtJson(objectMapper.writeValueAsString(ext));
            behaviorEventService.save(event);
        } catch (Exception e) {
            log.warn("写广告行为埋点失败 adId={}, type={}, error={}", adId, eventType, e.getMessage());
        }
    }

    private Map<String, Object> ext(AdSlotItem item, String requestId) {
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("adId", item.getAdId());
        ext.put("campaignId", item.getCampaignId());
        ext.put("slotId", item.getSlotId());
        ext.put("bid", item.getBid());
        ext.put("pctr", item.getPctr());
        ext.put("ecpm", item.getEcpm());
        ext.put("requestId", requestId);
        return ext;
    }

    private Long categoryOf(AdEventRequest request) {
        return request.getCategoryId();
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
