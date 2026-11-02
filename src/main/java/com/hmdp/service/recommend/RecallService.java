package com.hmdp.service.recommend;

import com.hmdp.dto.ActivityCandidate;
import com.hmdp.dto.RecommendContext;

import java.util.List;

/**
 * 单路召回。
 */
public interface RecallService {

    String source();

    List<ActivityCandidate> recall(RecommendContext context);
}
