package com.hmdp.search.index;

import lombok.Data;

@Data
public class SearchIndexEvent {

    public static final String UPSERT = "UPSERT";
    public static final String DELETE = "DELETE";

    private String eventType;
    private Long activityId;
    private Long version;
}
