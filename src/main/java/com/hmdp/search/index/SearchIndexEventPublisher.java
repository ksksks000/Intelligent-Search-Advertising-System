package com.hmdp.search.index;

public interface SearchIndexEventPublisher {

    void publish(Long activityId, String eventType);
}
