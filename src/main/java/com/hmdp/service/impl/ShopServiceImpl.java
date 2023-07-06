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
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
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

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if(shop==null){
            return Result.fail("店铺不存在！");
        }
        //7.返回
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id){
        //1.从redis查商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+ id);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //3.存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if(shopJson !=null){
            //即不为空还不是null 那就是空字符串了
            return null;
        }
        //4.实现缓存重建

        //4.1获取互斥锁
        String lockKey = "lock:lock"+id;
        Shop shop = null;
        try {
            boolean lock = tryLock(lockKey);
            //4.2 判断是否获取成功
            //4.3获取失败  休眠 重试
            if(!lock){

                Thread.sleep(50);
                queryWithMutex(id);

            }
            //4.4 获取成功 查数据写入redis
            //释放锁
            shop = getById(id);
            //5.不存在 返回错误
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

            //返回错误信息
            if(shop==null){
                return null;
            }
            //6.存在 添加到redis缓存中
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        //7.返回
        return shop;
    }
    //线程池
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//
//    public Shop queryWithLogicalExpire(Long id){
//        //1.从redis查商户缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+ id);
//        //2.判断是否存在
//        if(StrUtil.isBlank(shopJson)){
//            //3.不存在 直接返回
////            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
////            return shop;
//            return null;
//        }
//        //4.命中高 先把json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        // 5.判断缓存是否过期
//        if(expireTime.isAfter(LocalDateTime.now())){
//            ///5.1未过期 直接返店铺信息
//            return shop;
//        }
//
//
//        //5.2已过期 需要缓存重建
//        //6.重建缓存
//        //6.1获取互斥锁
//        String  lockKey  = LOCK_SHOP_KEY+id;
//        boolean islock = tryLock(lockKey);
//        //6.2 判断是否拿到互斥锁
//        if(islock){
//            //成功 开启独线程  实现缓存重建
//            CACHE_REBUILD_EXECUTOR.submit(()->{
//                //重建缓存
//                try {
//                    this.saveShhop2Redis(id,30L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    //释放锁
//                    unlock(lockKey);
//                }
//
//            });
//        }
//
//        //6.2.2拿到了 开启一个新线程
//
//        //拿到数据写入redis 并设置过期时间
//        //6.2.3 释放互斥锁
//
//        //判断命中的是否是空值
//
//        return shop;
//    }
//
//    public Shop queryWithPassThrough(Long id){
//        //1.从redis查商户缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+ id);
//        //2.判断是否存在
//        if(StrUtil.isNotBlank(shopJson)){
//            //3.存在 直接返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        //判断命中的是否是空值
//        if(shopJson !=null){
//            //即不为空还不是null 那就是空字符串了
//            return null;
//        }
//        //4.不存在 根据id查询数据库
//        Shop shop = getById(id);
//        //5.不存在 返回错误
//        //将空值写入redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
//
//        //返回错误信息
//        if(shop==null){
//            return null;
//        }
//        //6.存在 添加到redis缓存中
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        //7.返回
//        return shop;
//    }
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);

    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("商铺不存在！");
        }
        //更新数据库
        updateById(shop);
        ///删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }


    public void saveShhop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询商铺数据  预热
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
        //3.写入redis
    }
}

