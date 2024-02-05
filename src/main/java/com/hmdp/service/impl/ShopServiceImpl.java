package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    /**
     * 根据id查店铺（改进后）
     *
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
//        Shop shop = queryWithPassThrough(id);
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);// 使用工具类/

        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);// 使用工具类

        // 逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        // 7、返回商铺信息
        return Result.ok(shop);
    }


    /**
     * 根据id查店铺（解决缓存穿透）
     *
     * @param id
     * @return
     */
//    public Shop queryWithPassThrough(Long id) {
//        String key = CACHE_SHOP_KEY + id;
//        // 1、从 redis 查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        // 2、判断是否存在
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 3、redis 中存在，直接返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        // 判断命中的是否是 空值
//        if ("".equals(shopJson)) {
//            // 返回错误信息
//            return null;
//        }
//
//        // 4、redis 不存在，查询数据库中是否存在
//        Shop shop = getById(id);
//        if (shop == null) {
//            // 将空值写入 redis  （解决缓存穿透）                    2L
//            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            // 5、数据库中不存在，返回错误信息
//            return null;
//        }
//
//        // 6、数据库中存在，写入 redis                                            30L
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        // 7、返回商铺信息
//        return shop;
//    }


    /**
     * 根据id查店铺（互斥锁解决缓存击穿）
     *
     * @param id
     * @return
     */
//    public Shop queryWithMutex(Long id) {
//        String key = CACHE_SHOP_KEY + id;
//        // 1、从 redis 查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        // 2、判断是否存在
//        if (StrUtil.isNotBlank(shopJson)) {
//            // 3、redis 中存在，直接返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        // 判断命中的是否是 空值
//        if ("".equals(shopJson)) {
//            // 返回错误信息
//            return null;
//        }
//
//        // 4、缓存重建
//        // 4.1、获取互斥锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        Shop shop = null;
//        try {
//            boolean isLock = tryLock(lockKey);
//            // 4.2、判断是否获取成功
//            if (!isLock) {
//                // 4.3、失败，则休眠等待
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//
//            // 4.4、成功，根据id查询数据库
//            shop = getById(id);
//            // 模拟重建延时
//            Thread.sleep(200);
//            // 5、不存在，返回错误信息
//            if (shop == null) {
//                // 将空值写入 redis  （解决缓存穿透）                    2L
//                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//                // 5、数据库中不存在，返回错误信息
//                return null;
//            }
//
//            // 6、数据库中存在，写入 redis                                            30L
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            // 7、释放互斥锁
//            unlock(lockKey);
//        }
//
//        // 8、返回商铺信息
//        return shop;
//    }


    // 模拟线程池
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查店铺（逻辑过期解决缓存击穿）
     *
     * @param id
     * @return
     */
//    public Shop queryWithLogicalExpire(Long id) {
//        String key = CACHE_SHOP_KEY + id;
//        // 1、从 redis 查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        // 2、判断是否存在
//        if (StrUtil.isBlank(shopJson)) {
//            // 3、redis 中不存在，返回null
//            return null;
//        }
//        // 4、命中，吧json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//
//        // 5、判断是否过期
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            // 5.1、未过期，返回店铺对象
//            return shop;
//        }
//
//        // 5.2、已过期，重建缓存
//        // 6、缓存重建
//
//        // 6.1获取互斥锁
//        String localKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(localKey);
//        // 6.2、判断锁是否获取成功
//        if (isLock) {
//            // 6.3、获取锁成功，开启独立线程，实现缓存重建
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    // 缓存重建
//                    saveShopRedis(id, 20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    // 释放锁
//                    unlock(localKey);
//                }
//            });
//        }
//
//        // 6.4、锁获取失败，返回过期的店铺信息
//        return shop;
//    }


    /**
     * 加锁
     *
     * @param key
     * @return
     */
//    private boolean tryLock(String key) {
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }

    /**
     * 释放锁
     *
     * @param key
     */
//    private void unlock(String key) {
//        stringRedisTemplate.delete(key);
//    }


    /**
     * 将数据和，逻辑过期时间封装到新的对象
     *
     * @param id
     * @param expireSeconds
     */
    public void saveShopRedis(Long id, Long expireSeconds) {
        // 1、查询店铺数据
        Shop shop = getById(id);
        try {
            // 模拟缓存重建延迟
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        // 2、封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3、写入 redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    @Override
    @Transactional  // 事务
    public Result update(Shop shop) {

        Long id = shop.getId();

        if (id == null) {
            return Result.fail("店铺id不能为null");
        }

        // 1、更新数据库
        updateById(shop);

        // 2、删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
