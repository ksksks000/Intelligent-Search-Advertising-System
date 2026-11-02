package com.hmdp.repository;

import com.hmdp.service.ISeckillVoucherService;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;

/**
 * DB 库存扣减：条件更新保证不会扣成负数，失败由上层抛出异常触发事务回滚。
 */
@Repository
public class SeckillVoucherStockRepository {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    public boolean deduct(Long voucherId) {
        return seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
    }
}
