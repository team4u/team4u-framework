# DurableStore 存储 SPI 与 KV 适配

在持久化执行体系中，引擎本身不绑定任何具体的物理数据库技术。快照信封的读写与 CAS 乐观锁推进通过极简的 **`DurableStore` SPI** 进行解耦。

本文将详细解析 `DurableStore` SPI 规范、内置的内存实现以及基于 `team4u-kv` 的生产级多后端适配器 `KvDurableStore`。

---

## 存储架构全景

```mermaid
graph TD
    D["DurableMachine 状态机"] -->|"load / compareAndSet"| SPI["DurableStore (存储 SPI 接口)"]
    
    SPI --> M["InMemoryDurableStore<br/>(team4u-flow-durable 核心包)<br/>ConcurrentHashMap 纯内存实现"]
    SPI --> K["KvDurableStore<br/>(team4u-flow-durable-kv 适配包)<br/>基于统一 KvStore 抽象"]
    
    K --> KV_REDIS["RedisKvStore (分布式缓存/持久化)"]
    K --> KV_JDBC["JdbcKvStore (关系型数据库 MySQL/PostgreSQL)"]
    K --> KV_TIERED["TieredKvStore (内存 + Redis 分层多级缓存)"]
```

---

## `DurableStore` SPI 接口契约

`DurableStore` 包含两个核心方法与一个可选能力方法：

```java
package com.team4u.framework.flow.durable.store;

import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DurableStore {
    /**
     * 加载指定 executionId 的最新快照。
     *
     * @param executionId 执行流水号
     * @return 若存在返回快照 Optional，否则返回 empty
     */
    Optional<DurableSnapshot> load(String executionId);

    /**
     * 针对指定执行实例执行 CAS 乐观锁比较与更新。
     *
     * @param executionId      执行流水号
     * @param expectedRevision 期望的当前版本号。特别地，-1 表示仅在记录不存在时创建（用于 start 命令）
     * @param update           待持久化的新快照实例
     * @return true 表示 CAS 成功；false 表示版本冲突已被其他实例抢占修改
     */
    boolean compareAndSet(String executionId, long expectedRevision, DurableSnapshot update);

    /**
     * 可选能力：扫描已到达定时唤醒时刻（firstWakeAt <= now）的 ACTIVE 快照。
     * 默认不支持（返回 empty），供外部定时唤醒调度器使用。
     */
    default Optional<List<DurableSnapshot>> scanDue(Instant now, int limit) {
        return Optional.empty();
    }
}
```

### 到期扫描与定时唤醒调度

引擎在提交快照时会把帧栈中最早的 wake/deadline 冗余写入信封字段 `firstWakeAt`
（仅 ACTIVE 快照非空，终态快照恒为 null）。`scanDue` 基于该字段过滤：

- 返回 `firstWakeAt <= now` 的 ACTIVE 快照，按到期时间升序，最多 `limit` 条；
- 调度器逐条调用 `recover(executionId)` 驱动推进；
- 多调度器并发扫描到同一到期执行时，CAS 乐观锁保证仅一方 recover 成功，另一方得到
  `REVISION_CONFLICT` 后自然让位；
- 不支持高效扫描的后端（如大键量 Redis SCAN）返回 `empty`，应维护外部到期索引
  （如 ZSET）或独立延迟队列实现定时唤醒。

---

## 内存存储实现：`InMemoryDurableStore`

位于 `team4u-flow-durable` 核心包内，基于 `ConcurrentHashMap` 实现，适合单元测试、集成验证与本地快速原型开发：

```java
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;

DurableStore memoryStore = new InMemoryDurableStore();

DurableRuntime runtime = DurableRuntime.builder(memoryStore)
        .build();
```

`load` / `compareAndSet` 与内置的 KV 实现遵循同一套严格参数校验契约：

- `executionId` 不能为 null 且不能为空白字符串，否则抛出 `NullPointerException` / `IllegalArgumentException`；
- `update` 快照不能为 null，且其 `executionId` 必须与传入的存储键一致；
- `expectedRevision` 必须 >= -1（-1 表示“不存在时创建”），且 `update.revision()` 必须等于 `expectedRevision + 1`；
- 内存实现支持 `scanDue` 到期扫描（全量遍历内存表并按 firstWakeAt 升序截取）。

---

## 生产级适配：`KvDurableStore` (`team4u-flow-durable-kv`)

在生产环境中，流程快照需要持久化至外部存储（如 Redis、MySQL、PostgreSQL 等），并在分布式多实例部署时支持集群协同。

`team4u-flow-durable-kv` 将 `DurableStore` 桥接到了框架统一的 `KvStore` 抽象上：

### 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-durable-kv</artifactId>
</dependency>
```

### 构建与装配

`KvDurableStore` 自动将 `DurableSnapshot` 转换为紧凑的 `DurableSnapshotDto`，并通过 `CasCapable` 乐观锁进行安全原子写入：

```java
import com.team4u.framework.flow.durable.kv.KvDurableStore;
import com.team4u.framework.flow.durable.store.DurableStore;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.redis.RedisKvStore;

// 1. 获取底层 KvStore 实例 (如 RedisKvStore 或 JdbcKvStore)
KvStore redisStore = new RedisKvStore(redisTemplate);

// 2. 构建 KvDurableStore：终态快照 1 天归档淘汰，非终态快照永不过期（推荐策略）
long oneDayTtlMs = 24 * 3600 * 1000L;
DurableStore durableStore = new KvDurableStore(
        redisStore, 
        "flow_durable",   // 存储空间名（SpaceKey 的 space 部分，非空且不得包含 ':' 或空白）
        oneDayTtlMs,      // terminalTtlMillis: COMPLETED/CANCELLED 快照 1 天后自动清理
        0L                // activeTtlMillis: ACTIVE/SUSPENDED 快照永不过期
);

// 3. 构建 DurableRuntime
DurableRuntime runtime = DurableRuntime.builder(durableStore)
        .build();
```

### 构造器参数说明

| 构造器 | 参数说明 |
| :--- | :--- |
| `KvDurableStore(store)` | 使用默认空间名 `flow_durable`，永不过期 |
| `KvDurableStore(store, space)` | 指定空间名，永不过期 |
| `KvDurableStore(store, space, ttlMillis)` | 指定空间名与终态 TTL（非终态永不过期；兼容旧签名，语义修复版） |
| `KvDurableStore(store, space, terminalTtlMillis, activeTtlMillis)` | 按生命周期分流 TTL（推荐） |
| `KvDurableStore(store, space, terminalTtlMillis, activeTtlMillis, clock)` | 完整参数，额外指定计算过期时间戳的时钟源 |

- **`space`（存储空间名）**：作为 `SpaceKey` 的命名空间部分，用于在同一个 `KvStore` 中隔离不同业务
  或不同应用的快照数据（如 `flow_durable`、`payment-flow`）；要求非空且不得包含 `':'` 或任何空白
  字符（构造期真实校验，违反抛 `IllegalArgumentException`）。它不是 Redis Key 前缀拼接参数，
  底层键的具体编码由 `KvStore` 实现决定；
- **`terminalTtlMillis`（终态 TTL）**：写入 COMPLETED/CANCELLED 快照时附带的存活时长，到期后由
  存储后端自动淘汰，实现历史归档数据清理；小于等于 0 表示永不过期；
- **`activeTtlMillis`（非终态 TTL）**：写入 ACTIVE/SUSPENDED 快照时附带的存活时长，**默认 0 表示
  永不过期（推荐）**。挂起等待人工审批等长周期流程可能停留 SUSPENDED 数天甚至数月，若对非终态
  设置较短 TTL 会静默删除仍在推进中的执行状态，导致后续 resume/recover 直接
  `EXECUTION_NOT_FOUND`；若确要设置，必须确保远大于业务最长挂起时长；
- **CAS 能力要求**：底层 `KvStore` 必须实现（或通过装饰链提供）`CasCapable` 能力，否则构造时抛出
  `IllegalArgumentException`；
- **到期扫描（scanDue）**：已实现基于 `ScanCapable` 能力的到期扫描（详见上文「到期扫描与定时
  唤醒调度」）；底层不支持扫描能力的后端返回 `empty`，需维护外部到期索引或延迟队列。

### Spring Boot 生产完整装配类示例

```java
@Configuration
public class DurableFlowAutoConfiguration {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Bean
    public DurableStore durableStore() {
        // 基于 Redis：终态快照 7 天归档淘汰，非终态（挂起/退避中）永不过期
        KvStore redisKvStore = new RedisKvStore(redisTemplate);
        long sevenDaysTtl = 7 * 24 * 3600 * 1000L;
        return new KvDurableStore(redisKvStore, "app_flow_durable", sevenDaysTtl, 0L);
    }

    @Bean
    public DurableRuntime durableRuntime(DurableStore durableStore) {
        // 配置确定性 Jackson 序列化器
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        SerializerStateMapper jsonMapper = new SerializerStateMapper(
                "json:jackson", 1, mapper::writeValueAsBytes, b -> mapper.readValue(b, Object.class));

        return DurableRuntime.builder(durableStore)
                .stateMapper(CompositeStateMapper.withDefault(jsonMapper))
                .operationResolver(BeanFlows.resolver()) // 自动绑定 Spring Bean 步骤
                .build();
    }

    @Bean
    public DurableExecutable<OrderRequest, Receipt> orderDurableExecutable(
            DurableRuntime durableRuntime, Flow<OrderRequest, Receipt> orderFlow) {
        // 编译绑定 (flowId="order-fulfillment", flowVersion=1)
        return durableRuntime.compile(orderFlow, "order-fulfillment", 1);
    }
}
```

---

## 常见后端存储方案选型与表结构设计

| 后端方案 | 适用场景 | 架构优势 | 运维注意事项 |
| :--- | :--- | :--- | :--- |
| **`RedisKvStore`** | 高并发短/中周期流程、微秒级状态机 | 极高的读写吞吐，原生支持 TTL 自动过期淘汰 | 需开启 AOF / RDB 持久化防止机房断电丢状态 |
| **`JdbcKvStore`** | 金融交易、长事务审批、永久审计归档 | 严格 ACID、支持 SQL 复杂条件查询与报表统计 | 需建立 `execution_id` 唯一索引与 `revision` 乐观锁字段 |
| **`TieredKvStore`** | 超高频读取流程 | L1 本地内存 + L2 Redis，极大降低网络 I/O | 写操作自动广播同步，适用于读多写少场景 |

### 关系型数据库 (JDBC) 推荐表结构

```sql
CREATE TABLE `flow_durable_snapshot` (
  `execution_id` varchar(128) NOT NULL COMMENT '执行实例流水号',
  `flow_id` varchar(128) NOT NULL COMMENT '流程标识',
  `flow_version` int NOT NULL COMMENT '流程拓扑版本号',
  `revision` bigint NOT NULL COMMENT '单调递增乐观锁版本号',
  `lifecycle` varchar(32) NOT NULL COMMENT '生命周期状态 (ACTIVE/SUSPENDED/COMPLETED/CANCELLED)',
  `awaiting_point` varchar(128) DEFAULT NULL COMMENT '当前等待的挂起点',
  `wake_at` datetime(3) DEFAULT NULL COMMENT '计划定时唤醒时间戳',
  `snapshot_payload` longblob NOT NULL COMMENT 'DurableSnapshot 序列化二进制载荷',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`execution_id`),
  KEY `idx_wake_at` (`lifecycle`, `wake_at`),
  KEY `idx_flow_version` (`flow_id`, `flow_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流程持久化快照表';
```

---

## 关联章节与进一步阅读

- 了解 Durable 状态机与检查点机制：[Durable 状态机与 CAS 检查点机制](flow-durable-core.md)
- 了解两段式 CAS 恢复与定时唤醒：[Durable 两段式恢复协议与 PersistentPolicy](flow-durable-resume.md)
- 了解快照存储槽位与确定性编解码：[快照存储结构与 StateMapper 编解码](flow-durable-snapshot.md)
- 探索统一 KV 存储组件更多特性：[键值存储组件 (team4u-kv)](../kv/README.md)
