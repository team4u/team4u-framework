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

`DurableStore` 仅包含两个核心方法：

```java
package com.team4u.framework.flow.durable.store;

import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
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
}
```

---

## 内存存储实现：`InMemoryDurableStore`

位于 `team4u-flow-durable` 核心包内，基于 `ConcurrentHashMap` 实现，适合单元测试、集成验证与本地快速原型开发：

```java
import com.team4u.framework.flow.durable.store.InMemoryDurableStore;

DurableStore memoryStore = new InMemoryDurableStore();

DurableRuntime runtime = DurableRuntime.builder(memoryStore)
        .build();
```

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

// 2. 构建 KvDurableStore (可指定 Key 前缀与快照 TTL 过期时间)
long oneDayTtlMs = 24 * 3600 * 1000L;
DurableStore durableStore = new KvDurableStore(
        redisStore, 
        "flow:durable:", // Redis Key 前缀
        oneDayTtlMs      // 可选 TTL: 完成或空闲快照 1 天后自动清理
);

// 3. 构建 DurableRuntime
DurableRuntime runtime = DurableRuntime.builder(durableStore)
        .build();
```

### Spring Boot 生产完整装配类示例

```java
@Configuration
public class DurableFlowAutoConfiguration {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Bean
    public DurableStore durableStore() {
        // 基于 Redis 构建带 7 天自动 TTL 过期清理的持久化存储
        KvStore redisKvStore = new RedisKvStore(redisTemplate);
        long sevenDaysTtl = 7 * 24 * 3600 * 1000L;
        return new KvDurableStore(redisKvStore, "app:flow:durable:", sevenDaysTtl);
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
