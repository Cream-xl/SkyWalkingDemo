package com.cream.skywalkingdemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体。
 * 注意：nickname 刻意不建索引，用于制造 LIKE '%keyword%' 全表扫描慢 SQL。
 */
@Data
@TableName("user_info")
public class UserInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** 无索引字段，慢查询目标列 */
    private String nickname;

    private String phone;

    private String email;

    private String address;

    private LocalDateTime createTime;
}
