# 快速开始

本文介绍如何在项目中快速使用 `team4u-lease` 发布并处理排他性租约任务。

---

## 1. 引入依赖

生产环境推荐引入 JDBC 持久化模块：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-lease-jdbc</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

本地开发或测试可使用内存模块：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-lease-memory</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## 2. 初始化数据库表结构 (仅 JDBC 版需要)

在 MySQL 中创建 `lease_task` 表及专用复合索引（对应 `team4u-lease-jdbc` 的 `schema/lease_task_mysql.sql`）：

```sql
CREATE TABLE IF NOT EXISTS `lease_task` (
    `task_id`          VARCHAR(64)  NOT NULL COMMENT '全局唯一任务ID',
    `task_group`       VARCHAR(128) NOT NULL COMMENT '任务分组',
    `task_type`        VARCHAR(128) NOT NULL COMMENT '任务类型',
    `payload`          TEXT                  COMMENT '任务载荷数据',
    `business_key`     VARCHAR(256) NULL     COMMENT '业务幂等键',
    `state`            VARCHAR(32)  NOT NULL COMMENT 'READY, RUNNING, CLOSED',
    `outcome`          VARCHAR(32)  NULL     COMMENT 'SUCCEEDED, FAILED, CANCELLED',
    `failure_reason`   VARCHAR(64)  NULL     COMMENT '失败原因',
    `priority`         INT          NOT NULL DEFAULT 0 COMMENT '优先级 (数值越大越先执行)',
    `delivery_count`   INT          NOT NULL DEFAULT 0 COMMENT '投递尝试总次数',
    `failure_count`    INT          NOT NULL DEFAULT 0 COMMENT '历史执行失败次数',
    `worker_id`        VARCHAR(128) NULL     COMMENT '当前持有租约的Worker ID',
    `lease_token`      VARCHAR(128) NULL     COMMENT '当前租约令牌',
    `lease_expires_at` BIGINT       NOT NULL DEFAULT 0 COMMENT '租约过期时间戳(毫秒)',
    `visible_at`       BIGINT       NOT NULL COMMENT '就绪可见时间戳(毫秒)',
    `created_at`       BIGINT       NOT NULL COMMENT '创建时间戳(毫秒)',
    `updated_at`       BIGINT       NOT NULL COMMENT '更新时间戳(毫秒)',
    `version`          BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁行版本号',
    `error_message`    TEXT                  COMMENT '最后一次失败或取消信息',
    `attributes_json`  TEXT                  COMMENT '扩展属性JSON',
    PRIMARY KEY (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 抢占优化索引：针对 READY 就绪任务快速排序拉取
CREATE INDEX IF NOT EXISTS `idx_lease_task_acquire_ready`
    ON `lease_task` (`task_group`, `state`, `visible_at`, `priority`, `created_at`, `task_id`);

-- 抢占优化索引：针对 RUNNING 超时任务进行宕机故障接管
CREATE INDEX IF NOT EXISTS `idx_lease_task_acquire_expired`
    ON `lease_task` (`task_group`, `state`, `lease_expires_at`, `priority`, `created_at`, `task_id`);

-- 业务幂等唯一索引：同一 taskGroup 内 businessKey 全局唯一
CREATE UNIQUE INDEX IF NOT EXISTS `uk_lease_task_business`
    ON `lease_task` (`task_group`, `business_key`);

-- 运维查询与归档统计辅助索引
CREATE INDEX IF NOT EXISTS `idx_lease_task_worker`
    ON `lease_task` (`worker_id`, `state`);

CREATE INDEX IF NOT EXISTS `idx_lease_task_type`
    ON `lease_task` (`task_group`, `task_type`, `state`);

CREATE INDEX IF NOT EXISTS `idx_lease_task_closed_reason`
    ON `lease_task` (`state`, `outcome`, `failure_reason`);
```

---

## 3. 发布任务 (Producer)

```java
import com.team4u.framework.lease.api.LeaseProducer;
import com.team4u.framework.lease.model.LeasePublishRequest;
import com.team4u.framework.lease.model.LeasePublishResult;
import com.team4u.framework.lease.jdbc.JdbcLeaseBackend;

import javax.sql.DataSource;

// 1. 初始化后端存储（传入 DataSource 数据源）
DataSource dataSource = ...;
JdbcLeaseBackend backend = new JdbcLeaseBackend(dataSource);
LeaseProducer producer = backend;

// 2. 幂等发布任务（若相同 taskGroup + businessKey 记录已存在，则不重复建档并返回已有快照）
LeasePublishResult result = producer.publishIfAbsent(LeasePublishRequest.builder()
        .taskGroup("order-center")
        .taskType("order-timeout-cancel")
        .businessKey("ORDER_100862026")
        .payload("{\"orderId\": \"100862026\"}")
        .priority(10) // 优先级
        .delayMillis(15 * 60 * 1000L) // 15 分钟后延迟就绪可见
        .build());

if (result.isCreated()) {
    System.out.println("成功创建新租约任务, taskId = " + result.getTaskId());
} else {
    System.out.println("命中已有业务任务, taskId = " + result.getTaskId());
}
```

---

## 4. 处理任务 (Worker)

```java
import com.team4u.framework.lease.handler.DefaultLeaseTaskHandlerRegistry;
import com.team4u.framework.lease.runtime.LeaseWorker;
import com.team4u.framework.lease.runtime.LeaseWorkerPolicy;

// 1. 创建并注册本地任务处理器
DefaultLeaseTaskHandlerRegistry registry = new DefaultLeaseTaskHandlerRegistry();

registry.register("order-center", "order-timeout-cancel", context -> {
    System.out.println("Worker 抢占到任务, taskId=" + context.getTaskId()
            + ", payload=" + context.getPayload()
            + ", deliveryCount=" + context.getDeliveryCount());

    // 业务处理逻辑（若耗时较长，可随时调用 context.requestHeartbeat() 立即触发续租）
    cancelUnpaidOrder(context.getPayload());

    // 普通 LeaseTaskHandler 正常返回后，框架会自动调用 close(SUCCEEDED) 提交成功终态
    // 若抛出未捕获异常，框架会自动调用 close(FAILED) 并将失败原因标记为 HANDLER_EXCEPTION
});

// 2. 配置 Worker 策略并启动
LeaseWorker worker = new LeaseWorker(
        backend,
        registry,
        LeaseWorkerPolicy.builder()
                .workerId("worker-node-1")
                .leaseMillis(30_000L)           // 租约时长 30 秒
                .heartbeatEnabled(true)         // 启用后台自动定时续约（默认每 leaseMillis/3 续约一次）
                .pollWaitMillis(1_000L)         // 队列为空时长轮询等待 1 秒
                .build()
);

// 启动后台工作线程
worker.start("order-lease-worker");

// 3. 应用停机时优雅关闭（等待正在执行中的任务完成并安全关闭心跳线程池）
// worker.shutdownGracefully(5000L);
```

---

## 下一步

- 了解租约令牌、状态机与 At-Least-Once 语义：[租约核心模型与状态机](lease-model.md)
- 深入 Worker 线程模型、心跳续约与优雅停机：[Worker 执行与心跳续约](lease-worker.md)
- 探索数据库索引与并发乐观锁优化：[存储后端实现](lease-backend.md)
- 任务控制台管理与手动重试：[运维管控与查询服务](lease-admin.md)
- 查阅完整生产级实战案例：[实战案例](lease-sample.md)

