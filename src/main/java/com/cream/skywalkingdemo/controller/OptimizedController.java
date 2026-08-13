package com.cream.skywalkingdemo.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cream.skywalkingdemo.entity.OrderInfo;
import com.cream.skywalkingdemo.entity.UserInfo;
import com.cream.skywalkingdemo.mapper.OrderInfoMapper;
import com.cream.skywalkingdemo.mapper.UserInfoMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 优化后的对照接口，用于验证「优化前后指标差异」。
 * 场景 1（无索引慢 SQL）无需改代码，直接对 nickname 加索引即可，见 sql/init.sql 注释。
 */
@RestController
@RequestMapping("/opt")
public class OptimizedController {

    private final UserInfoMapper userInfoMapper;
    private final OrderInfoMapper orderInfoMapper;

    public OptimizedController(UserInfoMapper userInfoMapper, OrderInfoMapper orderInfoMapper) {
        this.userInfoMapper = userInfoMapper;
        this.orderInfoMapper = orderInfoMapper;
    }

    /**
     * 场景 2 优化版：把 N 次单查改成一次 IN 批量查询。
     */
    @GetMapping("/nplus1")
    public Map<String, Object> nplus1() {
        long start = System.currentTimeMillis();
        List<OrderInfo> orders = orderInfoMapper.selectList(
                new LambdaQueryWrapper<OrderInfo>()
                        .orderByDesc(OrderInfo::getId)
                        .last("LIMIT 100")
        );

        List<Long> userIds = orders.stream()
                .map(OrderInfo::getUserId)
                .distinct()
                .toList();
        // 一次 IN 查询替代循环单查
        List<UserInfo> users = userInfoMapper.selectBatchIds(userIds);
        long cost = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orders", orders.size());
        result.put("users", users.size());
        result.put("costMs", cost);
        result.put("note", "1 次订单查询 + 1 次 IN 批量查询");
        return result;
    }

    /**
     * 场景 3 优化版：缩小事务范围。
     * 查询与 sleep 都不持锁，只有最后的 UPDATE 短暂持锁。
     */
    @GetMapping("/bigTx")
    public Map<String, Object> bigTx(@RequestParam(defaultValue = "1") Long id) throws InterruptedException {
        long start = System.currentTimeMillis();

        // 普通查询，不加锁
        OrderInfo order = orderInfoMapper.selectById(id);

        // 业务耗时在事务外，不持有行锁
        Thread.sleep(3000);

        if (order != null) {
            order.setRemark("updated-" + System.currentTimeMillis());
            // 单条 UPDATE 自动提交，持锁时间极短
            orderInfoMapper.updateById(order);
        }

        long cost = System.currentTimeMillis() - start;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("costMs", cost);
        result.put("note", "sleep 不持锁，并发请求不再锁等待");
        return result;
    }
}
