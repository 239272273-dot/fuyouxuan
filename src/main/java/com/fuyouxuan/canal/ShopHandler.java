package com.fuyouxuan.canal;

import com.fuyouxuan.entity.Shop;
import com.fuyouxuan.utils.CacheInvalidationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.javatool.canal.client.annotation.CanalTable;
import top.javatool.canal.client.handler.EntryHandler;

import javax.annotation.Resource;

@Slf4j
@CanalTable("tb_shop")
@Component
public class ShopHandler implements EntryHandler<Shop> {

    @Resource
    private CacheInvalidationService cacheInvalidationService;

    @Override
    public void update(Shop before, Shop after) {
        log.info("Canal感知到商户更新, id={}, name={}", after.getId(), after.getName());
        cacheInvalidationService.evictShop(after.getId());
    }

    @Override
    public void delete(Shop shop) {
        log.info("Canal感知到商户删除, id={}", shop.getId());
        cacheInvalidationService.evictShop(shop.getId());
    }
}
