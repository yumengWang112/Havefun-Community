package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

//加载lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT ;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR  = Executors.newSingleThreadExecutor();
    @PostConstruct //当前类初始化完毕后就执行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private IVoucherOrderService proxy;
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
                while (true){
                    //获取队列中的订单信息
                    try {
                        VoucherOrder voucherOrder = orderTasks.take();
                        handleVoucherOrder(voucherOrder);
                    } catch (Exception e) {
                        log.error("处理订单异常",e);
                    }
                    //创建订单
                }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        //获取用户
        Long userId = voucherOrder.getUserId();
        //创建锁对象
       //获取锁
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean islock = lock.tryLock();//默认参数为-1  不等待
        if(!islock){
            log.error("不允许重复下单");
            return;
        }
        try {
            //获取代理对象

            proxy.createVoucher(voucherOrder);
        } finally {
            lock.unlock();
        }

    }

    @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本 完成下单操作
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );



        //2.判断结果是否为0
        int result1 = result.intValue();

        //2.1 不为0  没有购买资格
        if(result1 !=  0){
            return Result.fail(result1==1?"库存不足":"不能重复下单");
        }
        //2.2 为0 有购买资格 把下单信息保存到在阻塞队列
        //生成订单id
        long orderId = redisIdWorker.nextId("order");

        VoucherOrder voucherOrder = new VoucherOrder();
        //订单 id

        //用户id
        //登陆拦截器可以获取 用户 id

        //优惠券id
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);
        //添加到阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3.返回 订单id
        return Result.ok(orderId);
    }
//        @Override
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {
//        //查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断是否开始秒杀
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀活动还未开始！");
//        }
//        //判断秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀活动已经结束！");
//        }
//        //否  返回异常结果
//        //是 判断库存是否充足
//        if(voucher.getStock()<1){
//            return Result.fail("库存不足！");
//        }
//        //库存不足 返回异常信息
//        //库存充足 扣减库存
//        //一人一单
//        Long userId = UserHolder.getUser().getId();
//        //同一个用户才加锁
//        //id值一样 但toString()底层是new了一个字符串  所有还是一个全新的对象
//        //intern()如果池中已经包含一个等于这个string对象的字符串，就返回池中的字符串，否则就将该对象加到池中并引用
////        synchronized(userId.toString().intern()) {
////            //获取代理对象
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucher(voucherId);
////        }
//        //如果只对用于id加锁 事务未提交用线程并发  用户再进来也会出现问题  应该对create方法加锁 事务提交之后再 释放锁
//
//        //创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        //获取锁
//        RLock lock = redissonClient.getLock("order:" + userId);
//        boolean islock = lock.tryLock(1L, TimeUnit.SECONDS);//默认参数为-1  不等待
//        if(!islock){
//            return Result.fail("不允许重发下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucher(voucherId);
//        } finally {
//            lock.unlock();
//        }
//
//
//    }

    @Transactional
    public synchronized void createVoucher(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            int  count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            if(count > 0){
                log.error("用户已经购买成功！");
                return ;
            }
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock -1").
                    eq("voucher_id", voucherOrder.getVoucherId())
                    .gt("stock",0)
                    .update();
            //创建订单
            if(!success){
                log.error("库存不足！");
                return ;
            }

            save(voucherOrder);


            //返回订单id


        }


}
