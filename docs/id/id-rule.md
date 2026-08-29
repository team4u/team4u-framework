# 规则配置

序号规则描述「一个序号如何生成」：是否分组、从哪个存储计数、从几开始、步进多少、何时耗尽、是否循环、要不要本地号段、如何格式化。规则全部 JSON 化，通过[配置组件](../config/README.md)集中管理。

## 配置模型

`SeqRule`：

```java
@Data
public class SeqRule {
    private String store;
    private SeqGroupConfig group;
    private long start = 1L;
    private int step = 1;
    private Long maxValue;
    private boolean recycle;
    private int segment;
    private int seqLength;
    private String format;
}
```

| 属性 | 类型 | 必填 | 默认值 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `store` | String | 否 | 默认存储 | 存储名（`NamedKvStoreRegistry.global()` 注册名），见[快速开始](quick-start.md#多存储分工) |
| `group` | JSON | 否 | 不分组 | 分组配置，见[分组策略](id-group.md) |
| `start` | long | 否 | 1 | 初始值 |
| `step` | int | 否 | 1 | 步进：相邻两个序号的差值 |
| `maxValue` | Long | 否 | 无上限 | 最大值（含端点），达到后耗尽或循环 |
| `recycle` | boolean | 否 | false | 达到最大值后是否从 `start` 重新循环 |
| `segment` | int | 否 | 0 | 本地号段长度，大于 0 时启用号段模式 |
| `seqLength` | int | 否 | 0 | 序号补零长度，如 6 时序号 42 输出 `000042` |
| `format` | String | 否 | 补零序号 | 输出模板，变量 `${name}`/`${group}`/`${seq}` |

## 配置格式

配置键为 `seq.{规则标识}`，值为上述模型的 JSON：

```properties
seq.order={"segment":100}
seq.monthlyOrder={"group":{"format":"yyyyMM"},"start":1000,"maxValue":9999,"recycle":true}
```

最小配置（等效于从 1 开始、步进 1、无上限、直连计数）：

```json
{}
```

解析与校验约定：

- `step` 必须 ≥ 1，`segment` 必须 ≥ 0，`maxValue` 必须 ≥ `start`，违反抛 `SeqConfigException`（快速失败，配置错误是程序错误）；
- 未配置的属性按默认值生效，未知属性忽略（配置组件序列化约定）；
- 规则不存在时 `next`/`tryNext` 抛 `SeqConfigException`——不存在静默降级。

## 取值语义

序号取值遵循等差数列：`start, start+step, start+2*step, ...`

```text
计数位置 p（1-based，由计数器维护）
├── 可用数量 count = (maxValue - start) / step + 1   （无 maxValue 时为无限）
├── 序号值 = start + (p - 1) * step
├── 耗尽：p > count 且未开启 recycle → 拒绝取号
└── 循环：p > count 且开启 recycle → 位置取模 (p - 1) % count，无需重置底层计数器
```

示例：`start=1000, step=100, maxValue=1200` 生成 `1000, 1100, 1200` 后耗尽；开启 `recycle` 后为 `1000, 1100, 1200, 1000, ...`。

**烧号语义**：耗尽判定为纯算术，不回写存储。直连模式下耗尽后的取号调用仍会消耗一个计数位置（不发出业务序号）——计数器是无意义的大整数，不影响正确性；额度类场景通常按周期分组，计数键随周期轮转，烧号无累积代价。

## 计数键

计数状态由存储的 `CounterCapable` 维护，键结构为：

```text
{键空间}:{规则标识}.{分组标识}
```

- 键空间默认 `seq`，可经 `SequenceService` 构造参数自定义；
- 未分组时键为 `{键空间}:{规则标识}`；分组后分组标识参与键组成，分组变化即在新键上从 0 重新计数；
- JDBC 后端中每个键对应 `kv_counter` 表一行；Redis 后端为对应物理键。

计数器随键空间持续累加、无过期语义。周期重置由换键实现（新周期新键），旧键数据保留作审计，由业务按需清理（如按日期键前缀批量删除）。

## 规则热更新

规则加载基于配置组件的 `ConfigDrivenRegistry`（配置模式 `seq.*`）：

| 层级 | 机制 | 生效时机 |
| :--- | :--- | :--- |
| 规则对象 | 配置变更监听，先建新再替换、失败保旧 | 配置刷新后立即 |
| 本地号段 | 号段缓存键含规则内容指纹，规则变更后旧号段实例自然废弃，由 LRU 淘汰 | 下一次取号 |

```java
// 调整号段长度后自动生效，无需重启
source.putAndRefresh("seq.order", "{\"segment\":1000}");
```

> 注意：热更新只影响「如何取号」，已发出的序号与已累积的计数不回滚；调小 `maxValue` 时若计数已越过新上限，序号将立即耗尽。
