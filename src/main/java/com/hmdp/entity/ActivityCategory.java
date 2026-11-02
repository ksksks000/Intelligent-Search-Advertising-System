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
 * 活动类目读模型：复用 tb_shop_type，新增 parentId/level/path 表达层级。
 * 老接口 /shop-type/list 仍使用 ShopType，行为不变。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_shop_type")
public class ActivityCategory implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    private String icon;

    private Integer sort;

    private Long parentId;

    private Integer level;

    private String path;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
