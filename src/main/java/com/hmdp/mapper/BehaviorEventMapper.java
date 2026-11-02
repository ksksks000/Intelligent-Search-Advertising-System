package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.BehaviorEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface BehaviorEventMapper extends BaseMapper<BehaviorEvent> {

    @Select("SELECT DISTINCT user_id FROM tb_behavior_event "
            + "WHERE user_id IS NOT NULL AND event_time >= #{since}")
    List<Long> selectActiveUserIds(@Param("since") LocalDateTime since);

    @Select("SELECT DISTINCT device_id FROM tb_behavior_event "
            + "WHERE device_id IS NOT NULL AND user_id IS NULL AND event_time >= #{since}")
    List<String> selectActiveDeviceIds(@Param("since") LocalDateTime since);
}
