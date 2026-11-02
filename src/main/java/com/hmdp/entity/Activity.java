package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 活动/优惠内容主表。券、折扣、线下活动、新品、秒杀统一抽象为活动，
 * 通过 activityType 区分；券类活动通过 tb_voucher.activity_id 挂接履约信息。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_activity")
public class Activity implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int TYPE_VOUCHER = 0;
    public static final int TYPE_DISCOUNT = 1;
    public static final int TYPE_OFFLINE = 2;
    public static final int TYPE_NEW_PRODUCT = 3;
    public static final int TYPE_SECKILL = 4;

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_AUDITING = 1;
    public static final int STATUS_ONLINE = 2;
    public static final int STATUS_OFFLINE = 3;
    public static final int STATUS_EXPIRED = 4;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 商家ID，NULL 表示平台级活动 */
    private Long shopId;

    private String title;

    private String subTitle;

    private String description;

    /** 图片，逗号分隔 */
    private String images;

    /** 0优惠券 1折扣 2线下活动 3新品首发 4秒杀 */
    private Integer activityType;

    private Long categoryId;

    /** 标签，逗号分隔 */
    private String tags;

    private String city;

    private String area;

    private Double x;

    private Double y;

    /** 原价，分 */
    private Long originalPrice;

    /** 支付金额，分 */
    private Long payValue;

    /** 抵扣金额，分 */
    private Long actualValue;

    private Integer stock;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

    /** 0草稿 1待审 2在线 3下线 4过期 */
    private Integer status;

    private Integer qualityScore;

    /** 0手工 1由voucher回填 2由blog回填 */
    private Integer sourceType;

    private Long sourceId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
