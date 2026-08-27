# 常见案例

## 全局唯一标识

### 场景

- 生成全局唯一的数字标识，从 1 开始计数
- 多实例部署，性能要求高，本地缓存 100 个号段，用完再从远程获取

### 基于 JDBC 的配置

```properties
seq.order={"segment":100}
```

```java
// 默认存储为 JdbcKvStore（见快速开始）
SequenceService sequences = new SequenceService(configManager, new JdbcKvStore(dataSource));
```

### 基于 Redis 的配置

```properties
seq.order={"segment":100}
```

```java
SequenceService sequences = new SequenceService(configManager,
        new RedisKvStore(stringRedisTemplate));
```

其中：

- `segment=100` 即号段长度：本地批量取号，存储访问量降低 100 倍
- 未配置分组，所有请求共享同一计数键

### 使用

```java
long orderNo = sequences.next("order");
```

## 周期唯一标识

### 场景

- 在某个周期（如当月）内生成唯一数字标识，从 1000 开始计数
  - 2026-08-01 至 2026-08-31 为一个周期：1000, 1001, 1002 ……
  - 2026-09-01 起为另一个周期：1000, 1001, 1002 ……
- 本地缓存 100 个号段提升性能

### 配置

```properties
seq.monthlyOrder={"group":{"format":"yyyyMM"},"start":1000,"segment":100}
```

其中：

- `group.format=yyyyMM` 使用[日期分组](id-group.md#内置策略-date-周期重置)，按月重置（默认 `yyyyMMdd` 按天）
- `start=1000`，每个周期从 1000 开始
- `segment=100`，本地号段加速；旧月份的号段实例随 LRU 自动淘汰，无需任何过期配置

### 使用

```java
long orderNo = sequences.next("monthlyOrder");
```

月初第一次调用时分组标识变化（如 `202608`→`202609`），新分组独立计数，自然从 1000 开始。

## 固定额度分配

### 场景

- 某外部渠道接口每天调用额度为 10000，额度用完后调用方应停止调用
- 0 点后额度重置

### 配置

```properties
seq.channelQuota={"maxValue":10000,"segment":50}
```

其中：

- 未配置 `group.format`，默认按天分组（`yyyyMMdd`），每天一个新计数键
- `maxValue=10000`，每个周期最多可分配额度
- `segment=50`，每次从计数器批量获取 50 个额度缓存本地

### 使用

```java
Long quota = sequences.tryNext("channelQuota");

if (quota == null) {
    // 今日额度已用完
    return;
}

// 使用额度调用外部渠道
callChannel(quota);
```

序号耗尽即无额度：`tryNext` 返回 `null`，不抛异常；调用方以返回值判定即可。

## 序号循环使用

### 场景

- 生成 1~10000 的递增序号，用完后从 1 开始循环

### 配置

```properties
seq.cyclic={"maxValue":10000,"recycle":true}
```

生成效果：`..., 9998, 9999, 10000, 1, 2, 3, ...`

循环定位通过等差数列取模实现，不重置底层计数器，无并发竞争，见[规则配置](id-rule.md#取值语义)。

## 业务维度分组

### 场景

- 每个商户独立计数，序号从 1 开始
- 分组标识来自调用上下文而非系统时间

### 配置与使用

```properties
seq.merchantOrder={"group":{"type":"EXT","extKey":"merchantId"}}
```

```java
Map<String, Object> ext = Collections.singletonMap("merchantId", "M001");
long value = sequences.next("merchantOrder", ext);
```

每个商户获得独立的计数键；上下文缺少 `merchantId` 时抛 `SeqConfigException` 快速失败。更复杂的分组逻辑（如商户 + 日期）见[自定义分组策略](id-group.md#自定义分组策略)。

## 格式化单号

### 场景

- 生成形如 `ORD-202608-000042` 的业务单号：前缀 + 年月 + 6 位补零序号
- 单号格式规则化，业务代码不再手写拼接

### 配置

```properties
seq.orderNo={"format":"ORD-${group}-${seq}","seqLength":6,"group":{"format":"yyyyMM"}}
```

其中：

- `format` 输出模板，变量：`${name}`（规则标识）、`${group}`（分组标识）、`${seq}`（补零序号）
- `seqLength=6` 序号补零到 6 位
- `group.format=yyyyMM` 分组标识即年月，直接复用为单号的一部分

### 使用

```java
String orderNo = sequences.nextFormatted("orderNo");   // ORD-202608-000001
```

## 单元测试

### 场景

- 单元测试中不依赖数据库/Redis，用内存计数替代真实存储
- 验证按日分组的周期重置逻辑

### 用法

```java
public class OrderServiceTest {

    private final TestConfigContext config = TestConfigContext.create();
    private final TestKvContext kv = TestKvContext.create();
    private SequenceService sequences;

    @Before
    public void setUp() {
        config.put("seq.dailyOrder", "{}");
        sequences = new SequenceService(config.getConfigManager(), kv.store(),
                kv.clock());
    }

    @Test
    public void dailyReset() {
        assertEquals(1, sequences.next("dailyOrder"));
        assertEquals(2, sequences.next("dailyOrder"));

        kv.advanceMillis(24 * 3600_000L);   // 虚拟时钟推进一天
        assertEquals(1, sequences.next("dailyOrder"));
    }
}
```

内存计数与 JDBC/Redis 实现跑同一套契约测试（`AbstractSequencesContractTest`），行为一致，测试无需外部依赖。`TestConfigContext`/`TestKvContext` 由 `team4u-config-test`/`team4u-kv-test` 提供（test scope）。
