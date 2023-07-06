package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author wangyumeng
 * @version 1.0
 */
public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate  stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT ;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString()+"-";

    @Override
    public boolean tryLock(long timeouSec) {
        String key = KEY_PREFIX+name;
        //获取当前线程id

        long id = Thread.currentThread().getId();
        String threadId = ID_PREFIX+id;

        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, threadId, timeouSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        //调用lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX+Thread.currentThread().getId());
    }
    //    @Override
//    public void unlock() {
//        String key = KEY_PREFIX+name;
//        String threadId = ID_PREFIX+Thread.currentThread().getId();;
//        String s = stringRedisTemplate.opsForValue().get(key);
//        if(s.equals(threadId)){
//            stringRedisTemplate.delete(key);
//        }
//
//
//    }
}
