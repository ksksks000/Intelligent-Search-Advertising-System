package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_activity_stats")
public class ActivityStats implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "activity_id", type = IdType.INPUT)
    private Long activityId;

    private LocalDate statDate;

    private Long pv;

    private Long uv;

    private Long clickCnt;

    private Long orderCnt;

    private BigDecimal ctr;

    private BigDecimal cvr;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
