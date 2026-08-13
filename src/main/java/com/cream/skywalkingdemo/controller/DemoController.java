package com.cream.skywalkingdemo.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cream.skywalkingdemo.entity.OrderInfo;
import com.cream.skywalkingdemo.entity.UserInfo;
import com.cream.skywalkingdemo.mapper.OrderInfoMapper;
import com.cream.skywalkingdemo.mapper.UserInfoMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三个接口分别复现三类慢查询场景，专注制造慢查询现象，不做复杂业务。
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private final UserInfoMapper userInfoMapper;
    private final OrderInfoMapper orderInfoMapper;

    public DemoController(UserInfoMapper userInfoMapper, OrderInfoMapper orderInfoMapper) {
        this.userInfoMapper = userInfoMapper;
        this.orderInfoMapper = orderInfoMapper;
    }

    /**
     * 场景 1：无索引慢 SQL。
     * nickname 列无索引，LIKE 'keyword%' 前缀模糊查询触发全表扫描；
     * 加索引后可走 range 扫描，明显变快。
     */
    @GetMapping("/slowSql")
    public Map<String, Object> slowSql(@RequestParam(defaultValue = "用户1") String keyword) {
        long start = System.currentTimeMillis();
        List<UserInfo> list = userInfoMapper.selectList(
                new LambdaQueryWrapper<UserInfo>()
                        .likeRight(UserInfo::getNickname, keyword)
        );
        long cost = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sql", "SELECT * FROM user_info WHERE nickname LIKE '" + keyword + "%'");
        result.put("matched", list.size());
        result.put("costMs", cost);
        return result;
    }

    /**
     * 场景 2：N+1 查询。
     * 先查 100 条订单，再在循环里逐条查用户。单次 SQL 快，多次叠加总耗时高。
     */
    @GetMapping("/nplus1")
    public Map<String, Object> nplus1() {
        long start = System.currentTimeMillis();
        List<OrderInfo> orders = orderInfoMapper.selectList(
                new LambdaQueryWrapper<OrderInfo>()
                        .orderByDesc(OrderInfo::getId)
                        .last("LIMIT 100")
        );

        List<UserInfo> users = new ArrayList<>(orders.size());
        for (OrderInfo order : orders) {
            // 循环单查 -> N+1
            users.add(userInfoMapper.selectById(order.getUserId()));
        }
        long cost = System.currentTimeMillis() - start;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orders", orders.size());
        result.put("users", users.size());
        result.put("costMs", cost);
        result.put("note", "1 次订单查询 + " + orders.size() + " 次用户单查");
        return result;
    }

    /**
     * 场景 3：长事务锁等待。
     * FOR UPDATE 锁住订单行，sleep 模拟业务耗时，事务提交前一直持锁，
     * 并发请求会排队等锁。
     */
    @Transactional
    @GetMapping("/bigTx")
    public Map<String, Object> bigTx(@RequestParam(defaultValue = "1") Long id) throws InterruptedException {
        long start = System.currentTimeMillis();

        // 对目标行加排他锁，直到事务提交才释放
        OrderInfo order = orderInfoMapper.selectOne(
                new LambdaQueryWrapper<OrderInfo>()
                        .eq(OrderInfo::getId, id)
                        .last("FOR UPDATE")
        );

        // 模拟业务耗时，期间持锁 3 秒
        Thread.sleep(3000);

        if (order != null) {
            order.setRemark("updated-" + System.currentTimeMillis());
            orderInfoMapper.updateById(order);
        }

        long cost = System.currentTimeMillis() - start;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("costMs", cost);
        result.put("note", "持锁 3 秒后提交；并发访问同一 id 会锁等待");
        return result;
    }
}
