package com.fuyouxuan.config;

import com.fuyouxuan.entity.SeckillVoucher;
import com.fuyouxuan.service.ISeckillVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static com.fuyouxuan.utils.RedisConstants.SECKILL_STOCK_KEY;

/** Loads missing flash-sale stock keys without overwriting live reservations. */
@Slf4j
@Component
public class SeckillStockBootstrap implements InitializingBean {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void afterPropertiesSet() {
        for (SeckillVoucher voucher : seckillVoucherService.list()) {
            Boolean created = stringRedisTemplate.opsForValue().setIfAbsent(
                    SECKILL_STOCK_KEY + voucher.getVoucherId(), voucher.getStock().toString());
            if (Boolean.TRUE.equals(created)) {
                log.info("Welfare flash stock loaded, voucherId={}, stock={}",
                        voucher.getVoucherId(), voucher.getStock());
            }
        }
    }
}
