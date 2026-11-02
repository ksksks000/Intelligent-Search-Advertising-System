package com.hmdp.ad.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_ad_billing_log")
public class AdBillingLog implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String billingId;

    private String requestId;

    private Long adId;

    private Long campaignId;

    private Long advertiserId;

    private String userKey;

    private String deviceId;

    private String ip;

    private String eventType;

    private String slotId;

    private Long bid;

    private Long cost;

    private Integer status;

    private LocalDateTime eventTime;

    private LocalDateTime createTime;
}
