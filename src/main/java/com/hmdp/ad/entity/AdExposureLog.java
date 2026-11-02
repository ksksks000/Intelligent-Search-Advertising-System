package com.hmdp.ad.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_ad_exposure_log")
public class AdExposureLog implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int CONVERSION_PENDING = 0;
    public static final int CONVERSION_DONE = 1;
    public static final int CONVERSION_TIMEOUT = 2;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String requestId;

    private Long adId;

    private Long campaignId;

    private String userKey;

    private String deviceId;

    private String ip;

    private String slotId;

    private String city;

    private Long bid;

    private BigDecimal pctr;

    private BigDecimal ecpm;

    private LocalDateTime exposedAt;

    private Integer clicked;

    private LocalDateTime clickTime;

    private Integer converted;

    private LocalDateTime conversionTime;

    private Integer conversionStatus;

    private String extJson;
}
