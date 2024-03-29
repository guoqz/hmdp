package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 存数据并设置时间
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }


    /**
     * 存入数据并设置逻辑过期时间
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入 redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1、从 redis 查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2、判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3、redis 中存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是 空值
        if ("".equals(json)) {
            // 返回错误信息
            return null;
        }

        // 4、redis 不存在，查询数据库中是否存在
        R r = dbFallback.apply(id);
        if (r == null) {
            // 将空值写入 redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 5、数据库中不存在，返回错误信息
            return null;
        }

        // 6、数据库中存在，写入 redis
        this.set(key, r, time, unit);
        // 7、数据
        return r;
    }



    // 模拟线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查店铺（逻辑过期解决缓存击穿）
     *
     * @param id
     * @return
     */
    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix,ID id,Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1、从 redis 查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2、判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3、redis 中不存在，返回null
            return null;
        }
        // 4、命中，吧json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5、判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1、未过期，返回店铺对象
            return r;
        }

        // 5.2、已过期，重建缓存
        // 6、缓存重建

        // 6.1获取互斥锁
        String localKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(localKey);
        // 6.2、判断锁是否获取成功
        if (isLock) {
            // 6.3、获取锁成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 缓存重建
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(localKey);
                }
            });
        }

        // 6.4、锁获取失败，返回过期的店铺信息
        return r;
    }


    /**
     * 加锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }



}
