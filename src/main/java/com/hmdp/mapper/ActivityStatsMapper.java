package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.ActivityStats;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

public interface ActivityStatsMapper extends BaseMapper<ActivityStats> {

    /**
     * 原子累加曝光/点击/下单，并重算 ctr/cvr。
     * 使用 VALUES() 兼容 MySQL 8.4；列名与插入值不会产生歧义。
     */
    @Insert("INSERT INTO tb_activity_stats " +
            "(activity_id, stat_date, pv, uv, click_cnt, order_cnt, ctr, cvr) " +
            "VALUES (#{activityId}, #{statDate}, #{pv}, 0, #{clickCnt}, #{orderCnt}, " +
            "CASE WHEN #{pv} > 0 THEN #{clickCnt} / #{pv} ELSE 0 END, " +
            "CASE WHEN #{clickCnt} > 0 THEN #{orderCnt} / #{clickCnt} ELSE 0 END) " +
            "ON DUPLICATE KEY UPDATE " +
            "pv = pv + VALUES(pv), " +
            "click_cnt = click_cnt + VALUES(click_cnt), " +
            "order_cnt = order_cnt + VALUES(order_cnt), " +
            "ctr = IF(pv > 0, click_cnt / pv, 0), " +
            "cvr = IF(click_cnt > 0, order_cnt / click_cnt, 0)")
    int upsert(@Param("activityId") Long activityId,
               @Param("statDate") LocalDate statDate,
               @Param("pv") long pv,
               @Param("clickCnt") long clickCnt,
               @Param("orderCnt") long orderCnt);
}
