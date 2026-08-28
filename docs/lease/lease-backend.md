# 存储后端

普通业务代码只依赖 `TaskQueue` 和 `TaskWorker`。这一页面向两类人：

- 准备把学习项目搬上生产的工程师；
- 要自己实现存储后端或审查后端正确性的开发者。

## 选择 Memory 还是 JDBC

| 后端 | 构造方式 | 数据在哪里 | 适用场景 |
| :--- | :--- | :--- | :--- |
| Memory | `new InMemoryLeaseBackend()` | 当前 JVM 内存 | 学习、单元测试、单进程任务 |
| JDBC | `new JdbcLeaseBackend(dataSource)` | MySQL 表 | 多进程部署、重启后任务保留 |

Memory 后端进程退出后任务就消失，不能用于跨进程容灾。生产上多实例部署、发布重启、机器故障后任务仍需存在时，应使用 JDBC 后端。

## Memory 后端

```java
InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
TaskQueue orders = Leases.queue(backend, "orders");
```

测试需要控制时间时可以注入 `Clock`：

```java
InMemoryLeaseBackend backend =
        new InMemoryLeaseBackend(Clock.systemUTC());
```

实现要点：

- 任务按 `taskId` 保存，幂等任务按 `(queue, taskType, deduplicationKey)` 建索引；
- 每个队列、每个任务类型都有候选索引；
- 候选按 `priority DESC, createdAt ASC, taskId ASC` 排序；
- 一个可中断锁保护所有状态变更，保证任务记录和索引同时更新；
- 同一 JVM 内多个 Worker 也必须通过后端抢占，不能绕过“同一任务同时只给一个 Worker”的约束。

## JDBC 后端

先执行建表脚本。脚本文件在 JDBC 模块 classpath 中：

```text
schema/lease_task_mysql.sql
```

然后传入你现有的 `DataSource`：

```java
JdbcLeaseBackend backend = new JdbcLeaseBackend(dataSource);
TaskQueue orders = Leases.queue(backend, "orders");
```

如需显式指定方言：

```java
JdbcLeaseBackend backend =
        new JdbcLeaseBackend(dataSource, new MySqlLeaseDbDialect());
```

默认方言就是 MySQL。`DataSource` 来自 HikariCP、Druid、Spring 或应用服务器；JDBC 后端不内置连接池，也不负责建表。

任务属性会写入 `attributes_json` 并通过 `JsonUtil` 编解码。应用必须显式添加 `team4u-serializer-jackson`，或通过 `META-INF/services/com.team4u.framework.serializer.json.JsonSerializerPolicy` 注册自定义 provider；`team4u-lease-jdbc` 本身不会传递绑定任何 JSON 引擎。

H2 测试路径使用 JDBC 模块测试资源中的 `lease_task_h2.sql` 建表；生产 MySQL 使用上文的生产 DDL。测试可以调用包内构造器注入确定性毫秒时钟。

### 表结构

单表名为 `lease_task`，核心列如下：

| 列 | 作用 |
| :--- | :--- |
| `task_id` | 主键 |
| `queue_name`, `task_type` | 队列和任务类型 |
| `payload`, `attributes_json` | 业务数据和属性 |
| `deduplication_key` | 幂等键，与队列、类型组成唯一键 |
| `status` | 五个状态枚举名 |
| `visible_at` | 最早可执行时间 |
| `worker_id`, `lease_token`, `lease_expires_at` | 当前执行权信息 |
| `attempt_count` | 被抢占次数 |
| `priority`, `created_at`, `version` | 排序、创建时间和乐观锁版本 |
| `error_message` | 失败或取消原因 |

关键约束和索引：

```sql
PRIMARY KEY (task_id),
UNIQUE KEY uk_lease_task_dedup (queue_name, task_type, deduplication_key),
KEY idx_lease_task_pending (queue_name, status, task_type, visible_at, priority, created_at, task_id),
KEY idx_lease_task_expired (queue_name, status, task_type, lease_expires_at, priority, created_at, task_id),
KEY idx_lease_task_query (queue_name, task_type, status, worker_id, created_at, task_id)
```

标识列使用 `utf8mb4_bin`，所以队列名、任务类型和幂等键区分大小写。数据库时间和执行时间使用毫秒时间戳。

### 抢占如何保持排他

MySQL 方案不是先查再改，而是“候选查询 + 条件更新”：

1. 查询当前队列、当前订阅类型下可见的 `PENDING` 任务，以及执行权已过期的 `RUNNING` 任务；
2. 按 `priority DESC, created_at ASC, task_id ASC` 排序；
3. 对候选执行条件 `UPDATE`，只有版本和状态仍满足时才写入新的执行权；
4. 成功后 `attempt_count` 加 1，`version` 加 1。

每个执行权都有新的 `lease_token`。心跳和结果写回必须带同一个 `taskId + workerId + leaseToken`，并且执行权未过期。这样即使旧 Worker 恢复，也不能覆盖新 Worker 的结果。

### 生产风险

当前 JDBC 测试主要运行在 H2 的 MySQL 兼容模式上，并用单独测试校验 DDL 语法特征、索引和大小写行为。仓库测试还没有连接真实 MySQL。

首次上生产前应至少完成：

1. 在目标 MySQL 版本上执行 `lease_task_mysql.sql`；
2. 用真实连接跑一次应用启动和任务执行；
3. 观察抢占 SQL 的执行计划，确认索引被使用；
4. 在多实例环境下验证发布、重启和任务接管。

表结构是破坏性版本。旧版本 `lease_task` 表不能直接给新代码使用，需要按当前 schema 迁移或重建。

## 自定义后端：SPI

如果要接入其他数据库或存储，实现这个接口：

```java
public interface LeaseBackend extends LeasePublisher, LeaseRuntimeClient,
        LeaseAdminService, LeaseQueryService {
}
```

四个接口的职责：

| 接口 | 方法 | 职责 |
| :--- | :--- | :--- |
| `LeasePublisher` | `submit` | 创建任务和幂等冲突处理 |
| `LeaseRuntimeClient` | `acquire`, `heartbeat`, `close`, `release` | Worker 执行权、心跳和结果写回 |
| `LeaseAdminService` | `complete`, `reschedule`, `retry`, `update`, `updateAndReschedule` | 无执行权的查询和管理变更 |
| `LeaseQueryService` | `get`, `getByDeduplicationKey`, `list` | 队列内查询和分页 |

运行时协议对象：

| 对象 | 作用 |
| :--- | :--- |
| `TaskSubscription.of(queue, taskTypes)` | Worker 的精确类型订阅 |
| `LeaseGrant` | 抢占成功后的任务快照和执行权 |
| `LeaseHandle` | `taskId + workerId + leaseToken` 校验三元组 |
| `LeaseCompletion` | 成功、失败或取消的终态写回 |
| `LeaseRetry` | 延迟重试和可选数据更新 |

SPI 使用毫秒时间；业务 API 的 `Duration` 和 `Instant` 由 core 层转换。

### 契约测试

自定义后端应引入测试支撑模块：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-lease-test</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

然后继承契约测试：

```java
public class MyLeaseBackendContractTest extends AbstractLeaseRuntimeContractTest {
    @Override
    protected LeaseBackend createBackend() {
        return new MyLeaseBackend();
    }
}
```

还应分别运行：

- `AbstractLeaseStateSemanticsContractTest`
- `AbstractLeaseAdminContractTest`
- `AbstractLeaseQueryContractTest`
- `AbstractLeaseEpochOverflowContractTest`

这些测试固定了五态流转、精确类型订阅、幂等键三元组、优先级、执行权接管、fencing、管理面条件和毫秒溢出行为。全部通过后，自定义后端才能接入 `Leases.queue(...)` 和 `TaskWorker`。
