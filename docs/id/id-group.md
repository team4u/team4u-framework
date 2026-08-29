# 分组策略

分组决定序号**何时重新开始**。分组标识参与计数键（`{规则标识}.{分组标识}`），分组标识变化后在新键上重新计数，序号自然从头开始。

## 分组接口

```java
public interface GroupKeyPolicy extends KeyedPolicy<String> {

    /**
     * 生成分组标识；返回 null 或空串视为无分组
     */
    String groupKey(Context context);

    @Data
    class Context {
        private final String ruleId;          // 规则标识
        private final SeqGroupConfig config;  // 分组配置
        private final Map<String, Object> ext;// 调用方透传的扩展属性
        private final Clock clock;            // 时钟，测试可注入虚拟时钟
    }
}
```

- 策略标识（`key()`）即配置中的 `group.type`；
- `ext` 为 `next(name, ext)` 透传的扩展属性，供业务维度分组使用；
- 输出约束：分组标识不允许包含 `:`（与 kv 组件 `SpaceKey` 键约束一致），违反抛 `SeqConfigException`。

## 工厂注册

分组策略注册到 `GroupKeyPolicies`（基于 `KeyedPolicyRegistry`，读路径无锁）：

```java
// 任意环境：注册到全局注册表，对所有 SequenceService 实例生效
GroupKeyPolicies.global().register(new MerchantGroupKeyPolicy());

// Spring 环境：全局注册表声明为 Bean，配合策略自动发现
@Bean
@PolicyAutoRegister
public PolicyRegistry<GroupKeyPolicy> groupKeyPolicies() {
    return GroupKeyPolicies.global().registry();
}
```

同名策略重新注册即覆盖，实现热更新。内置策略 `DATE` 与 `EXT` 默认注册。

## 内置策略 DATE：周期重置

按时间格式生成分组标识，时间源为上下文时钟：

```java
public class DateGroupKeyPolicy implements GroupKeyPolicy {

    @Override
    public String groupKey(Context context) {
        return DateTimeFormatter.ofPattern(context.getConfig().getFormat())
                .format(ZonedDateTime.now(context.getClock()));
    }
}
```

| 属性 | 类型 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- |
| `format` | String | `yyyyMMdd` | 分组标识的时间格式 |

常用格式与重置周期：

| format | 分组标识示例 | 重置周期 |
| :--- | :--- | :--- |
| `yyyyMMdd`（默认） | 20260827 | 每天 |
| `yyyyMM` | 202608 | 每月 |
| `yyyy` | 2026 | 每年 |
| `yyyyMMddHH` | 2026082715 | 每小时 |

配置示例（按月分组）：

```json
{
  "group": { "format": "yyyyMM" },
  "start": 1000
}
```

效果：2026 年 8 月内分组标识均为 `202608`，9 月自动切换为 `202609`，序号重新从 1000 开始。

## 内置策略 EXT：业务维度分组

分组标识直接取调用上下文的扩展属性，适合按商户、渠道等维度隔离计数：

| 属性 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `extKey` | String | 是 | 从调用上下文取值的键 |

```json
{
  "group": { "type": "EXT", "extKey": "merchantId" }
}
```

```java
// 每个商户独立计数
Map<String, Object> ext = Collections.singletonMap("merchantId", "M001");
Number value = sequences.next("merchantOrder", ext);
```

快速失败语义：`extKey` 未配置或调用上下文缺少对应属性时抛 `SeqConfigException`——不会静默落到同一计数器上。

## 自定义分组策略

需要更复杂的分组逻辑（如按商户 + 日期组合）时，实现 `GroupKeyPolicy` 并注册：

```java
public class MerchantDailyGroupKeyPolicy implements GroupKeyPolicy {

    @Override
    public String key() {
        return "MERCHANT_DAILY";
    }

    @Override
    public String groupKey(Context context) {
        // 扩展参数经 attrs 透传
        String prefix = context.getConfig().getAttrs().get("prefix");
        String date = DateTimeFormatter.ofPattern("yyyyMMdd")
                .format(ZonedDateTime.now(context.getClock()));
        return prefix + context.getExt().get("merchantId") + "-" + date;
    }
}
```

```java
GroupKeyPolicies.global().register(new MerchantDailyGroupKeyPolicy());
```

```json
{
  "group": { "type": "MERCHANT_DAILY", "attrs": { "prefix": "M-" } }
}
```

> 注意：分组粒度越细，计数对象越多（JDBC 中为独立行、Redis 中为独立键），开启号段模式时每个分组占用一个本地号段实例（LRU 容量内自动淘汰），请结合业务评估活跃分组数量。
