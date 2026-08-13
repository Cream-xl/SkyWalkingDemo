package com.cream.skywalkingdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单表实体。
 */
@Data
@TableName("order_info")
public class OrderInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    /** 关联 user_info.id，N+1 场景通过它逐个反查用户 */
    private Long userId;

    private BigDecimal amount;

    private Integer status;

    private String remark;

    private LocalDateTime createTime;
}
