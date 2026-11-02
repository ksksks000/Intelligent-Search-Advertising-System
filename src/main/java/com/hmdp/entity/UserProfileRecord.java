package com.hmdp.entity;

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
@TableName("tb_user_profile")
public class UserProfileRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "user_key", type = IdType.INPUT)
    private String userKey;

    private Long userId;

    private String deviceId;

    private String narrative;

    private String interestTagsJson;

    private String adAffinityJson;

    private String avoidTagsJson;

    private String semanticQuery;

    private String semanticVectorId;

    private Integer version;

    private String source;

    private String promptVersion;

    private String modelVersion;

    private String inputHash;

    private LocalDateTime updatedAt;
}
