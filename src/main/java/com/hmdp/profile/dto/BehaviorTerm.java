package com.hmdp.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorTerm {

    private String tag;
    private double weight;
    private String action;
    private String timeBucket;
}
