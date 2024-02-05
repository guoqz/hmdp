package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
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


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 阻塞队列
    private final BlockingQueue<VoucherOrder> blockingQueue = new ArrayBlockingQueue<>(1024 * 1024);

    // 线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct  // 当前类初始化完毕后执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandle());
    }

    // 创建线程，异步处理
    private class VoucherOrderHandle implements Runnable {
        @Override
        public void run() {
            try {
                // 1获取对类中的订单信息
                VoucherOrder voucherOrder = blockingQueue.take();
                // 2创建订单
                handleVoucherOrder(voucherOrder);
            } catch (InterruptedException e) {
                log.error("处理订单异常", e);
                throw new RuntimeException(e);
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();

        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            log.error("不允许重复信息");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Resource
    private RabbitTemplate rabbitTemplate;  //     MQ

    /**
     * 秒杀下单
     *
     * @param voucherId
     * @return
     */
    // 使用 redission
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate
                .execute(SECKILL_SCRIPT,
                        Collections.emptyList(),
                        voucherId.toString(),
                        userId
                );

        //2.判断结果是否为0
        assert result != null;
        int value = result.intValue();
        if (value != 0) {
            return Result.fail(value == 1 ? "库存不足" : "不能重复下单");
        }

        //2.2为零 有购买资格，把下单信息保存到阻塞队列
        //.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //.1订单id 用id全局唯一生成器
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //.2用户id
        voucherOrder.setUserId(userId);
        //.3代金卷id
        voucherOrder.setVoucherId(voucherId);
        // 放入阻塞队列
//        blockingQueue.add(voucherOrder);//后面讲

        //放入mq      MQ
        String jsonStr = JSONUtil.toJsonStr(voucherOrder);
        rabbitTemplate.convertAndSend("X", "XA", jsonStr);

        // 获取代理对象（事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //返回订单id
        return Result.ok(orderId);

    }
/*
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1、查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2、判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 秒杀未开始
            return Result.fail("秒杀尚未开始");
        }

        // 3、判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 秒杀已结束
            return Result.fail("秒杀已结束");
        }

        // 4、判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        // 创建锁对象（自定义实现）
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        boolean isLock = lock.tryLock(1200);

        // 创建锁对象（Redisson实现）
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            return Result.fail("不允许重复下单");
        }
        try {
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }
*/


    /**
     * 创建订单
     *
     * @param voucherOrder
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5、一人一单
        Long userId = voucherOrder.getUserId();

        // 5.1、查询订单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (0 < count) {
            log.error("用户已经购买过一次了");
            return;
        }

        // 6、扣减库存
        boolean b = seckillVoucherService.update()
                .setSql("stock = stock - 1")    // set = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock = ?
                .update();
        if (!b) {
            log.info("库存不足，扣减库存失败");
            return;
        }

        save(voucherOrder);// 写入数据库

    }


    //  ====================   MQ    ==================

//    /**
//     * 消费者1
//     *
//     * @param message
//     * @param channel
//     * @throws Exception
//     */
//    @RabbitListener(queues = "QA")
//    public void receivedA(Message message, Channel channel) throws Exception {
//        String msg = new String(message.getBody());
//        log.info("正常队列:");
//        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
//        log.info(voucherOrder.toString());
//        save(voucherOrder);//保存到数据库
//
//        //数据库秒杀库存减一
//        Long voucherId = voucherOrder.getVoucherId();
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1") // set stock = stock - 1
//                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
//                .update();
//
//    }
//
//    /**
//     * 消费者2
//     *
//     * @param message
//     * @throws Exception
//     */
//    @RabbitListener(queues = "QD")
//    public void receivedD(Message message) throws Exception {
//        log.info("死信队列:");
//        String msg = new String(message.getBody());
//        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
//        log.info(voucherOrder.toString());
//        save(voucherOrder);
//
//        Long voucherId = voucherOrder.getVoucherId();
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1") // set stock = stock - 1
//                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
//                .update();
//
//    }


}

