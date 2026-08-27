# 存储后端实现

`TaskQueue` 和 `TaskWorker` 是业务层入口；`LeaseBackend` 是存储 SPI。后端实现者需要理解协议，普通业务调用方不需要。

## SPI 分层

`LeaseBackend` 聚合四个窄接口：

```java
public interface LeaseBackend extends LeasePublisher, LeaseRuntimeClient,
        LeaseAdminService, LeaseQueryService {
}
```

| 接口 | 方法 | 职责 |
| :--- | :--- | :--- |
| `LeasePublisher` | `submit` | 建档和幂等冲突处理 |
| `LeaseRuntimeClient` | `acquire`, `heartbeat`, `close`, `release` | 抢占、续租、终态和 retry release |
| `LeaseAdminService` | `complete`, `reschedule`, `retry`, `update`, `updateAndReschedule` | 无租约管理操作 |
| `LeaseQueryService` | `get`, `getByDeduplicationKey`, `list` | queue-scoped 查询和分页 |

运行时结果：

| `RuntimeResult` | 含义 |
| :--- | :--- |
| `APPLIED` | 写回成功 |
| `LEASE_LOST` | token、worker 或租约有效期不匹配 |
| `TASK_NOT_FOUND` | 任务不存在 |
| `TERMINAL` | 任务已是终态 |

管理结果为 `APPLIED`、`TASK_NOT_FOUND`、`TERMINAL`、`ACTIVE_LEASE_PRESENT`。SPI 的时间使用毫秒值，业务 API 的 `Duration`/`Instant` 在 core 门面中完成转换。

核心运行时协议对象：

- `TaskSubscription.of(queue, taskTypes)`: 精确类型订阅，不允许通配符；
- `LeaseGrant`: 抢占成功后的 `RUNNING` 快照和租约；
- `LeaseHandle`: `(taskId, workerId, leaseToken)` fencing 三元组；
- `LeaseCompletion`: 终态和可选 payload/attributes patch;
- `LeaseRetry`: retry delay 和可选 patch。

## InMemoryLeaseBackend

构造函数：

```java
InMemoryLeaseBackend backend = new InMemoryLeaseBackend();

InMemoryLeaseBackend controlledBackend =
        new InMemoryLeaseBackend(Clock.systemUTC());
```

实现结构：

- `tasksById`: 按 taskId 保存任务记录；
- `tasksByDedupKey`: 按 `(queue, taskType, deduplicationKey)` 保存幂等索引；
- `candidates`: `queue -> taskType -> CandidateSet` 的可见候选索引；
- 每个 `CandidateSet` 使用 `TreeSet` 保存 `priority DESC, createdAt ASC, taskId ASC` 的候选顺序；
- 一个可中断 `ReentrantLock` 保护所有状态变更，保证任务记录和索引同锁原子更新。

抢占时按订阅 type 遍历候选集，在符合条件的候选中做全局优先级比较；成功后写入新的 worker、token 和租约到期时间。同一 JVM 内多 Worker 也必须经过该索引和锁，不能绕过排他约束。

Memory 后端适合测试和单进程排他。进程重启后任务消失，不能用于跨进程容灾。

## JdbcLeaseBackend

构造函数：

```java
JdbcLeaseBackend backend = new JdbcLeaseBackend(dataSource);

JdbcLeaseBackend mysqlBackend =
        new JdbcLeaseBackend(dataSource, new MySqlLeaseDbDialect());
```

`DataSource` 由调用方的连接池提供。后端每次操作从 `DataSource` 获取连接，由底层 JDBC 工具负责释放；它不内置连接池或自动建表。

### MySQL schema

执行 JDBC 模块 classpath 中的：

```text
schema/lease_task_mysql.sql
```

表结构要点：

- 单表 `lease_task`，主键 `task_id`;
- `status` 存五个状态枚举名；
- `worker_id`、`lease_token`、`lease_expires_at` 只在有效执行记录上存在；
- `visible_at` 控制延迟任务资格；
- `attempt_count` 在抢占时递增；
- `version` 是乐观锁版本；
- `attributes_json` 保存 String 属性的 JSON 编码；
- 唯一键 `uk_lease_task_dedup (queue_name, task_type, deduplication_key)`;
- 抢占索引分别覆盖可执行 `PENDING` 和过期 `RUNNING`;
- 查询索引覆盖 type/status/worker 过滤与排序。

关键标识列使用 `utf8mb4_bin` 二进制排序，保证 queue、type 和 dedup key 区分大小写。DDL 使用 MySQL 8 兼容的内联索引语法，不依赖 MySQL 不支持的 `CREATE INDEX IF NOT EXISTS`。

### 抢占和 fencing

MySQL 方言生成 typed `UNION ALL` 候选查询：

- 第一段查找同 queue、精确 `task_type IN (...)`、`PENDING` 且 `visible_at <= now` 的任务；
- 第二段查找同 queue、精确 type、`RUNNING` 且 `lease_expires_at <= now` 的过期任务；
- 外层按 `priority DESC, created_at ASC, task_id ASC` 排序并限制候选数量。

随后对候选执行条件 UPDATE：

- 校验 taskId、queue、精确 task type 和期望 `version`;
- 校验任务仍满足 `PENDING 可见` 或 `RUNNING 租约过期`;
- 写入 `RUNNING`、新 worker、新 token、新到期时间；
- `attempt_count + 1`、`version + 1`。

CAS 成功后，后端还会用本次写入的 `queue/taskId/workerId/leaseToken/version` 精确重读所有权。如果极端并发下租约已被其他 Worker 再次接管，旧调用不会把别人的 handle 交给调用方，而是返回未抢到任务。

心跳和写回都携带 `taskId + workerId + leaseToken`，并要求租约未过期。心跳使用单调表达式，较晚执行的短心跳不会缩短已被更新的租约时间。

### 管理面条件更新

`complete`、`reschedule`、`update` 和 `updateAndReschedule` 都是条件 UPDATE：

- 终态拒绝；
- `RUNNING` 且租约未过期拒绝为 `ACTIVE_LEASE_PRESENT`;
- `RUNNING` 且租约已过期时允许管理面操作；
- `updateAndReschedule` 在同一条 UPDATE 中应用元数据、清租约并写入新的可见时间；
- `retry` 只允许 `FAILED` 任务回到 `PENDING`。

查询分页使用 `LIMIT/OFFSET`。offset 超过 `Integer.MAX_VALUE` 时会直接拒绝，避免整数溢出生成负偏移。

### 测试状态

JDBC 模块当前使用 H2 MySQL mode 执行契约测试，并有单独测试校验 MySQL DDL 语法特征、二进制排序和索引列。真实 MySQL 连接的集成测试尚未接入 Maven 测试流程；首次在真实 MySQL 执行 schema 和抢占压测前，不应假定 H2 已覆盖所有 MySQL 行为差异。

## 自定义后端

引入测试支撑模块：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-lease-test</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

实现 `LeaseBackend` 后，继承契约测试并覆盖 `createBackend()`:

```java
public class MyLeaseBackendContractTest extends AbstractLeaseRuntimeContractTest {
    @Override
    protected LeaseBackend createBackend() {
        return new MyLeaseBackend();
    }
}
```

除运行时契约外，还应分别继承：

- `AbstractLeaseStateSemanticsContractTest`
- `AbstractLeaseAdminContractTest`
- `AbstractLeaseQueryContractTest`
- `AbstractLeaseEpochOverflowContractTest`

这些基类固定了五态流转、精确类型订阅、dedup 三元组作用域、大小写、优先级、租约接管、fencing、管理面条件、属性 patch 语义和毫秒时间溢出拒绝。通过全部契约后，自定义后端才能接入 `Leases.queue(...)` 和 `TaskWorker`。

自定义实现还要遵守两个一致性点：

- 状态记录和候选索引必须同事务或同锁更新，避免出现“记录已变更但索引仍可抢占”的窗口；
- 幂等 key 冲突提交后必须重新读取现有任务并返回 `created=false`，不能只依赖插入异常。
