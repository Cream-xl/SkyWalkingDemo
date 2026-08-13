-- =====================================================================
-- SkyWalking 慢查询 Demo：建库 + 建表 + 造数
-- 执行方式：mysql -uroot -p < sql/init.sql
-- =====================================================================

CREATE DATABASE IF NOT EXISTS skywalking_demo
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE skywalking_demo;

-- ---------------------------------------------------------------------
-- 用户表：nickname 刻意「不建索引」，用于制造前缀模糊查询的全表扫描慢 SQL
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS user_info;
CREATE TABLE user_info (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    username    VARCHAR(64)  NOT NULL,
    nickname    VARCHAR(64)  NOT NULL COMMENT '无索引字段，慢查询目标列',
    phone       VARCHAR(20)  DEFAULT NULL,
    email       VARCHAR(128) DEFAULT NULL,
    address     VARCHAR(255) DEFAULT NULL,
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- ---------------------------------------------------------------------
-- 订单表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS order_info;
CREATE TABLE order_info (
    id          BIGINT         NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(64)    NOT NULL,
    user_id     BIGINT         NOT NULL COMMENT '关联 user_info.id',
    amount      DECIMAL(10, 2) DEFAULT '0.00',
    status      TINYINT        DEFAULT '0',
    remark      VARCHAR(255)   DEFAULT NULL,
    create_time DATETIME       DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单表';

-- ---------------------------------------------------------------------
-- 造数：10w 用户 + 20w 订单（MySQL 8 递归 CTE）
-- ---------------------------------------------------------------------
SET SESSION cte_max_recursion_depth = 300000;

INSERT INTO user_info (username, nickname, phone, email, address)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 100000
)
SELECT
    CONCAT('user_', n),
    CONCAT('用户', n),
    CONCAT('138', LPAD(n, 8, '0')),
    CONCAT('user_', n, '@example.com'),
    CONCAT('地址', n)
FROM seq;

INSERT INTO order_info (order_no, user_id, amount, status, remark)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 200000
)
SELECT
    CONCAT('ORD', LPAD(n, 8, '0')),
    (n MOD 100000) + 1,           -- user_id 落在 1..100000，保证能关联到用户
    ROUND(RAND() * 1000, 2),
    n MOD 5,
    CONCAT('remark_', n)
FROM seq;

-- =====================================================================
-- 【优化步骤】场景 1 的优化：对 nickname 建索引
-- 优化前：LIKE '用户1%' 全表扫描（type=ALL）
-- 优化后：走索引 range 扫描（type=range, key=idx_nickname）
-- 执行以下语句后重新调用 /demo/slowSql，再到 SkyWalking 对比指标。
-- =====================================================================
-- ALTER TABLE user_info ADD INDEX idx_nickname (nickname);
