package com.hmdp.utils;

/**
 * @author wangyumeng
 * @version 1.0
 */
public interface ILock {

    /*
    尝试获取锁
     */
    boolean tryLock(long timeouSec);

    /*
    释放锁
     */
    void unlock();
}
