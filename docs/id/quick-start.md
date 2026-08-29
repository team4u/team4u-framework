# 快速开始

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-id</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

计数存储按需引入 kv 后端（与 team4u-id 无强绑定）：

- 内存计数（单测、单实例）：`team4u-kv-core` 自带 `InMemoryKvStore`，无需额外依赖
- JDBC 计数：引入 `team4u-kv-store-jdbc`，由业务项目提供 `DataSource`
- Redis 计数：引入 `team4u-kv-store-redis`，由业务项目提供 `StringRedisTemplate`

## 最简用法：内存计数

```java
package demo;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import com.team4u.framework.id.core.SequenceService;
import com.team4u.framework.kv.memory.InMemoryKvStore;

public final class FirstSeqDemo {
    public static void main(String[] args) {
        InMemoryConfigSource source = new InMemoryConfigSource("demo", 0);
        source.put("seq.order", "{\"segment\":100}");
        ConfigManager configManager = ConfigManager.builder()
                .addSource(source).addWatcher(source).build();

        SequenceService sequences = new SequenceService(configManager, new InMemoryKvStore());
        System.out.println(sequences.next("order"));   // 输出: 1
    }
}
```

下面的片段默认持有 `sequences`（`SequenceService` 实例）：

```java
// 取号：配置缺失或非法抛 SeqConfigException，序号耗尽抛 SeqExhaustedException
long orderNo = sequences.next("order");

// 额度语义：耗尽返回 null 而非抛异常
Long quota = sequences.tryNext("channelQuota");
if (quota == null) {
    // 今日额度已用完
}

// 业务维度分组：扩展属性经分组策略参与计数键（见分组策略文档）
Long merchantNo = sequences.tryNext("merchantOrder",
        java.util.Collections.singletonMap("merchantId", "M001"));

// 格式化单号：规则配置 format/seqLength 后输出业务单号
String orderNoText = sequences.nextFormatted("orderNo");   // 如 ORD-202608-000042
```

## 使用 JDBC 计数

引入 `team4u-kv-store-jdbc` 后，将 JDBC 存储作为默认计数后端：

```java
// 启动自动建表（H2 与 MySQL 语法兼容）；生产环境建议 DBA 预先建表
KvStore kvStore = new JdbcKvStore(dataSource);

SequenceService sequences = new SequenceService(configManager, kvStore);
```

计数表 DDL（表名可经 `JdbcKvStore.Config.setCounterTableName` 自定义）：

```sql
CREATE TABLE IF NOT EXISTS kv_counter (
    space         VARCHAR(100) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    counter_value BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (space, name)
);
```

每个计数键（`规则标识.分组标识`）一行，`SELECT FOR UPDATE` 行锁保证并发递增原子且不丢失。

## 使用 Redis 计数

引入 `team4u-kv-store-redis` 后：

```java
KvStore kvStore = new RedisKvStore(stringRedisTemplate);

SequenceService sequences = new SequenceService(configManager, kvStore);
```

Redis 后端基于原生 `INCRBY` 原子递增，计数键与普通值键共享物理键空间（`seq:{规则标识}.{分组标识}`），同一键不可混用两种语义。

## 多存储分工

一套规则、多存储时，按名注册存储，规则中以 `store` 字段引用：

```java
// 注册命名存储（kv 组件全局注册表，同名重新注册即热更新）
NamedKvStoreRegistry.global().register("main", new JdbcKvStore(dataSource));
NamedKvStoreRegistry.global().register("fast", new RedisKvStore(stringRedisTemplate));

// 服务默认存储为 main
SequenceService sequences = new SequenceService(configManager,
        NamedKvStoreRegistry.global().get("main"));
```

```properties
# 高频序号走 Redis
seq.message={"store":"fast","segment":1000}
# 其余规则不配置 store，走默认存储
seq.order={"segment":100}
```

## 本地号段加速

每次取号都访问计数器（JDBC/Redis）存在网络与竞争开销。规则配置 `segment` 后，本地一次批量取 N 个序号，取号直接走本地内存：

```properties
seq.order={"segment":100}
```

- 存储访问量降低 100 倍：每 100 个序号仅 1 次存储访问；
- 无生产者线程、无清理器：号段耗尽时才惰性取下一批（见[本地号段](id-segment.md)）；
- 代价是趋势递增而非严格递增：多实例同时取号段时序号整体趋势增长，实例重启后未用完的号段作废（空洞）。

## 周期重置

规则配置 `group` 后，分组标识参与计数键，周期切换即重新计数：

```properties
# 按天分组：每天从 1 开始
seq.dailyOrder={"group":{}}
# 按月分组，从 1000 开始
seq.monthlyOrder={"group":{"format":"yyyyMM"},"start":1000}
```

## Spring 环境

```java
@Configuration
public class BeanConfig {

    @Bean
    public SequenceService sequenceService(ConfigManager configManager,
                                           DataSource dataSource) {
        return new SequenceService(configManager, new JdbcKvStore(dataSource));
    }

    // 可选：自定义分组策略声明为 Bean，配合注册表自动发现
    @Bean
    @PolicyAutoRegister
    public PolicyRegistry<GroupKeyPolicy> groupKeyPolicies() {
        return GroupKeyPolicies.global().registry();
    }
}
```

业务代码注入使用：

```java
@Service
public class OrderService {

    private final SequenceService sequences;

    public OrderService(SequenceService sequences) {
        this.sequences = sequences;
    }

    public void create() {
        long orderNo = sequences.next("order");
    }
}
```

## 单元测试：零外部依赖

```java
// 示例为 JUnit 4 风格（与框架测试栈一致），依赖 team4u-config-test / team4u-kv-test（test scope）
public class OrderServiceTest {

    private final TestConfigContext config = TestConfigContext.create();
    private final TestKvContext kv = TestKvContext.create();
    private SequenceService sequences;

    @Before
    public void setUp() {
        config.put("seq.order", "{\"segment\":100}");
        sequences = new SequenceService(config.getConfigManager(), kv.store());
    }

    @Test
    public void create() {
        assertEquals(1, sequences.next("order"));
        assertEquals(2, sequences.next("order"));
    }
}
```

`TestKvContext` 提供内存计数与虚拟时钟，`advanceMillis` 可精确推进时间验证按日分组的周期重置。

## 下一步

- 规则模型、计数键与热更新：[规则配置](id-rule.md)
- 周期重置与业务维度分组：[分组策略](id-group.md)
- 号段并发模型与空洞语义：[本地号段](id-segment.md)
- 各场景完整案例：[常见案例](id-sample.md)
