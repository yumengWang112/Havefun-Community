package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author wangyumeng
 * @version 1.0
 */
@Slf4j
@Component
public class CacheClient {
    private final  StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);

    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑 过期
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

    }
    //缓存穿透 缓存和数据库中都不存在
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key = keyPrefix + id;
        //1.从redis查商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(json)){
            //3.存在 直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if(json !=null){
            //即不为空还不是null 那就是空字符串了
            return null;
        }
        //4.不存在 根据id查询数据库
        R r = dbFallback.apply(id);
        //5.不存在 返回错误
        //将空值写入redis
        //返回错误信息
        if(r==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

        }
        //6.存在 添加到redis缓存中
        this.set(key,r,time,unit);
        return r;
    }


    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key = keyPrefix+id;
        //1.从redis查商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isBlank(json)){
            //3.不存在 直接返回
            return null;
        }
        //4.命中高 先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断缓存是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            ///5.1未过期 直接返店铺信息
            return r;
        }


        //5.2已过期 需要缓存重建
        //6.重建缓存
        //6.1获取互斥锁
        String  lockKey  = LOCK_SHOP_KEY+id;
        boolean islock = tryLock(lockKey);
        //6.2 判断是否拿到互斥锁
        if(islock){
            //成功 开启独线程  实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                //重建缓存
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }

        //6.2.2拿到了 开启一个新线程

        //拿到数据写入redis 并设置过期时间
        //6.2.3 释放互斥锁

        //判断命中的是否是空值

        return r;
    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);

    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


}
