package com.hmdp.ad.index;

public interface AdIndexEventPublisher {

    void publish(Long adId, Long campaignId, String eventType);
}
