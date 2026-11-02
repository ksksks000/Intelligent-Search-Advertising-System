package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;

public final class RecommendUtils {

    private RecommendUtils() {
    }

    public static String userKey(Long userId, String deviceId) {
        if (userId != null) {
            return "u" + userId;
        }
        if (StrUtil.isNotBlank(deviceId)) {
            return "d" + deviceId;
        }
        return "anon";
    }
}
