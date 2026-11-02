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
@TableName("tb_advertiser")
public class Advertiser implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int STATUS_DISABLED = 0;
    public static final int STATUS_ACTIVE = 1;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    private String contact;

    private Integer status;

    private Long balance;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
