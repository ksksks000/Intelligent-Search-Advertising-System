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
@TableName("tb_ad_campaign")
public class AdCampaign implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_ONLINE = 1;
    public static final int STATUS_PAUSED = 2;
    public static final int STATUS_ENDED = 3;

    public static final int TYPE_CPM = 1;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long advertiserId;

    private String name;

    private Long activityId;

    private Integer adType;

    private String title;

    private String description;

    private String image;

    private String landingUrl;

    /** 每次曝光出价，分 */
    private Long bid;

    private Long totalBudget;

    private Long dailyBudget;

    /** 逗号分隔；空表示不限 */
    private String targetTags;

    /** 逗号分隔类目 ID；空表示不限 */
    private String targetCategories;

    /** 空/ALL 表示不限 */
    private String targetCity;

    private String targetDevice;

    /** 0-23 逗号分隔；空表示全天 */
    private String targetHours;

    /** 目标 ad_affinity 标签；空表示不限 */
    private String targetAffinity;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private BigDecimal qualityFactor;

    private Integer freqPerDay;

    private Integer freqMinIntervalSeconds;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public boolean isDeliverable(LocalDateTime now) {
        if (status == null || status != STATUS_ONLINE) {
            return false;
        }
        if (startTime != null && now.isBefore(startTime)) {
            return false;
        }
        return endTime == null || !now.isAfter(endTime);
    }
}
