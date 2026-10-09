package com.fuyouxuan.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.fuyouxuan.dto.Result;
import com.fuyouxuan.entity.Shop;
import com.fuyouxuan.mapper.ShopMapper;
import com.fuyouxuan.service.IShopService;
import com.fuyouxuan.utils.CacheClient;
import com.fuyouxuan.utils.CacheInvalidationService;
import com.fuyouxuan.utils.SystemConstants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.fuyouxuan.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private CacheClient cacheClient;

    @Resource
    private Cache<Long, Shop> shopCache;

    @Resource
    private CacheInvalidationService cacheInvalidationService;

    @Override
    public Result queryById(Long id) {
        // 三级缓存：Caffeine → Redis → MySQL
        Shop shop = cacheClient.queryWithMultiLevel(
                shopCache, CACHE_SHOP_KEY, id, Shop.class, this::getById,
                CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿（备选）
        // Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿（备选）
        // Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("福利提供方不存在！");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("福利提供方id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.广播删除Redis和所有应用实例的本地缓存
        cacheInvalidationService.evictShop(id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current) {
        Page<Shop> page = query()
                .eq("type_id", typeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }
}
