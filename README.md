# SkyWalking 慢查询定位 / 分析 / 验证 Demo

一套可复现、可观测的 Demo，完整演示用 **Apache SkyWalking** 定位、分析、验证三类典型慢查询（无索引慢 SQL、N+1 循环查询、长事务锁等待）的全流程。

- 应用侧：Spring Boot 3.2 + MyBatis-Plus + MySQL 8，**零侵入**接入 SkyWalking Java Agent（不改业务代码）
- 服务端：SkyWalking 9.x（OAP + UI + Elasticsearch），Docker Compose 一键部署

---

## 1. 技术栈

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Spring Boot | 3.2.5 | Java 17 |
| MyBatis-Plus | 3.5.7 | `mybatis-plus-spring-boot3-starter` |
| MySQL | 8.x | 业务库 |
| SkyWalking | 9.7.0 | OAP + UI |
| Elasticsearch | 7.17.x | SkyWalking 存储 |
| SkyWalking Java Agent | 9.x | 字节码增强采集 JDBC 链路，无需埋点 |

## 2. 目录结构

```
SkyWalkingDemo
├── pom.xml                          # Maven 构建
├── src/main/java/com/cream/skywalkingdemo
│   ├── SkyWalkingDemoApplication.java   # 启动类（@MapperScan）
│   ├── entity/UserInfo.java             # 用户表实体
│   ├── entity/OrderInfo.java            # 订单表实体
│   ├── mapper/UserInfoMapper.java
│   ├── mapper/OrderInfoMapper.java
│   ├── controller/DemoController.java       # 三个慢查询接口
│   └── controller/OptimizedController.java  # 优化后对照接口
├── src/main/resources/application.yml
├── sql/init.sql                     # 建库建表 + 造数 + 索引优化语句
├── skywalking/docker-compose.yml    # ES + OAP + UI
├── skywalking/agent.config          # agent 关键配置
└── README.md
```

## 3. 端口规划

> 本机 8080 已被占用，因此应用与 UI 都避开了 8080。

| 端口 | 用途 |
| --- | --- |
| 8081 | Spring Boot 应用（HTTP 接口） |
| 11800 | OAP gRPC（Java Agent 上报） |
| 12800 | OAP HTTP REST（UI 查询） |
| 18080 | SkyWalking UI（宿主机 18080 → 容器 8080） |
| 9200 | Elasticsearch |

---

## 4. 快速启动

### 步骤 1：准备 MySQL

启动一个本地 MySQL 8（已有可跳过），确保账号密码与 `application.yml` 一致（默认 `root/root`，可按需改）。

### 步骤 2：建库建表 + 造数据

```bash
mysql -uroot -p < sql/init.sql
```

会创建 `skywalking_demo` 库，`user_info`（10w 行）、`order_info`（20w 行）。造数用递归 CTE，几秒到十几秒完成。

### 步骤 3：启动 SkyWalking 服务端

```bash
docker compose -f skywalking/docker-compose.yml up -d
docker compose -f skywalking/docker-compose.yml ps
```

- Linux 下 ES 可能因 `vm.max_map_count` 过小启动失败，先执行 `sudo sysctl -w vm.max_map_count=262144`；Windows/Docker Desktop 一般无需处理。
- 等 OAP 起来后访问 UI：<http://localhost:18080>（首次初始化需 1~2 分钟，页面数据会逐渐出现）。

### 步骤 4：下载并配置 SkyWalking Java Agent

1. 下载 Java Agent（与 OAP 同版本）：<https://skywalking.apache.org/downloads/>，解压到某目录（下面以 `C:\skywalking-agent` 为例）。
2. 用 `skywalking/agent.config` 覆盖 agent 根目录同名文件（或直接改），关键项：
   ```properties
   agent.service_name=skywalking-demo
   collector.backend_service=127.0.0.1:11800
   plugin.jdbc.trace_sql_parameters=true
   plugin.jdbc.sql_parameters_max_length=2048
   ```

### 步骤 5：IDEA 启动应用（VM options 挂载 agent）

IDEA 打开 Run/Debug Configurations，在 **VM options** 填入：

```
-javaagent:C:\skywalking-agent\skywalking-agent.jar
-Dskywalking.agent.service_name=skywalking-demo
-Dskywalking.collector.backend_service=127.0.0.1:11800
```

> 若已在 `agent.config` 里配好 service_name / backend_service，只需 `-javaagent:...` 一行即可。
> 说明：agent 通过字节码增强采集 JDBC 链路，业务代码零改动、无需埋点。

然后启动应用，确认日志正常、无数据库报错。

### 步骤 6：调用接口制造慢查询

```bash
# 场景 1：无索引慢 SQL（前缀模糊，全表扫描）
curl "http://localhost:8081/demo/slowSql?keyword=用户1"

# 场景 2：N+1 循环查询（1 + 100 次查询）
curl "http://localhost:8081/demo/nplus1"

# 场景 3：长事务锁等待（持锁 3 秒；并发触发锁排队）
curl "http://localhost:8081/demo/bigTx?id=1"
```

并发触发锁等待（两个终端同时执行，或复制下面一行）：

```bash
curl -s "http://localhost:8081/demo/bigTx?id=1" & curl -s "http://localhost:8081/demo/bigTx?id=1" & wait
```

观察返回的 `costMs`：第一个约 3000ms，第二个约 6000ms（额外等了 ~3 秒锁）。

---

## 5. SkyWalking 排查定位流程（核心）

模拟真实运维/开发排查步骤：

1. **看端点延迟**：UI 进入服务 `skywalking-demo`，打开「端点/Topology」页，观察三个接口的 P95/P99 延迟，找出高延迟端点。

2. **进入 Trace 瀑布图**：点开慢请求的 Trace，观察 JDBC/MySQL span 的「色块长度」，最长的 span 就是耗时大头。

3. **看 span 标签拿 SQL**：点开 JDBC span，在标签里找 `db.statement`，即可拿到捕获到的 SQL 语句。

4. **分场景判断根因**：
   - **场景 1（单条 JDBC span 耗时高）**：复制 SQL 到 MySQL 执行 `EXPLAIN ...`，看执行计划。`type=ALL` 且无 `key` → 缺少索引 / 全表扫描。
   - **场景 2（大量重复 JDBC span，单次快、数量多）**：瀑布图里出现上百个几乎相同的短 span → N+1 循环查询。
   - **场景 3（EXPLAIN 很快，但 span 耗时很高）**：SQL 本身快，span 却几十上百 ms 甚至秒级 → 长事务锁等待 / 连接池等待。

5. **执行优化**：见第 6 节。

6. **验证优化效果**：再次调用接口，回到 SkyWalking 观察 —— 端点 P95/P99 下降、Database 仪表盘 SQL 平均耗时下降、Trace 中 JDBC span 色块变短。

> **重要认知**：JDBC span 耗时 = 获取连接 + 网络 + **锁等待** + SQL 执行，**不等于 SQL 真实执行时间**。别拿 span 耗时当 SQL 执行时间，二者差距大时优先怀疑锁等待/连接池等待。

---

## 6. 三类问题的定位与优化

### 场景 1：无索引慢 SQL（`/demo/slowSql`）

- **现象**：`nickname` 无索引，`LIKE '用户1%'` 前缀模糊查询全表扫描 10w 行。
- **定位**：EXPLAIN 显示 `type=ALL`。
  ```sql
  EXPLAIN SELECT * FROM user_info WHERE nickname LIKE '用户1%';
  ```
- **优化**：加索引。
  ```sql
  ALTER TABLE user_info ADD INDEX idx_nickname (nickname);
  ```
- **验证**：再 EXPLAIN 看到 `type=range, key=idx_nickname`；再调接口耗时明显下降；SkyWalking Database 仪表盘该 SQL 平均耗时下降。

> 说明：这里用「前缀模糊 `x%`」而非「前后模糊 `%x%`」，因为 `%x%` 加了普通 B-tree 索引也走不了索引。前缀匹配才能通过「加索引」真实优化。

### 场景 2：N+1 循环查询（`/demo/nplus1`）

- **现象**：先查 100 条订单，再循环逐条查用户，共 **1 + 100 次** SQL。
- **定位**：Trace 瀑布图出现大量重复的短 JDBC span，单次 ~1ms 但数量多，总耗时长。
- **优化**：改成一次 `IN` 批量查询（已提供对照接口 `/opt/nplus1`）：
  ```java
  List<Long> userIds = orders.stream().map(OrderInfo::getUserId).distinct().toList();
  List<UserInfo> users = userInfoMapper.selectBatchIds(userIds); // 一次 IN
  ```
- **验证**：Trace 中 JDBC span 从 101 个降到 2 个，接口总耗时大幅下降。

### 场景 3：长事务锁等待（`/demo/bigTx`）

- **现象**：`SELECT ... FOR UPDATE` 锁行后 `sleep 3s`，事务提交前一直持锁；并发请求排队等锁。
- **定位**：单条 SQL 的 EXPLAIN 很快，但该 JDBC span 耗时是秒级 → 锁等待。
- **优化**：缩小事务范围 —— 查询与 sleep 都不持锁，只有最后的 UPDATE 短暂持锁（已提供 `/opt/bigTx`）：
  ```java
  OrderInfo order = orderInfoMapper.selectById(id);   // 不加锁
  Thread.sleep(3000);                                  // 业务耗时，不持锁
  orderInfoMapper.updateById(order);                   // 单条 UPDATE 自动提交
  ```
- **验证**：并发调用 `/opt/bigTx`，两个请求 `costMs` 都约 3000ms，不再排队。

---

## 7. 常见坑 & 注意点

1. **SQL 截断**：默认 `plugin.jdbc.sql_parameters_max_length=512`，长 SQL 参数会被截断，影响分析。测试环境调大到 2048。
   - 注意：网传 key 常写成 `plugin.jdbc.max_sql_length`，**实际有效的 key 是 `plugin.jdbc.sql_parameters_max_length`**。
2. **不要把 JDBC span 耗时当 SQL 执行时间**：span 耗时含获取连接、网络、锁等待、SQL 执行。差距大时优先怀疑锁等待/连接池等待。
3. **`trace_sql_parameters` 生产不能开**：会采集 SQL 参数值，泄露敏感数据（手机号、身份证等），还有性能开销。仅测试环境开启。
4. **端口冲突**：本 Demo 应用用 8081、UI 用 18080，均已避开 8080；若仍冲突，改 `application.yml` 的 `server.port` 或 `docker-compose.yml` 的 UI 映射。
5. **ES 内存 / max_map_count**：单机 ES 需 `vm.max_map_count=262144`（Linux），否则启动失败。
6. **OAP 数据延迟**：第一次请求后 UI 数据有 1~2 分钟延迟（聚合周期），属正常现象，稍等刷新。
7. **MySQL 账号密码**：默认 `root/root`，与实际环境不符时改 `application.yml` 的 `spring.datasource`。

## 8. 生产环境建议

- 关闭 `plugin.jdbc.trace_sql_parameters`（置 `false`），避免采集 SQL 参数。
- `plugin.jdbc.sql_parameters_max_length` 调回较小值或默认，控制链路数据量。
- OAP/ES 使用集群模式，`discovery.type=single-node` 仅为测试使用。
- 敏感字段在 SQL 层做脱敏，而非依赖 agent 关闭参数采集。
