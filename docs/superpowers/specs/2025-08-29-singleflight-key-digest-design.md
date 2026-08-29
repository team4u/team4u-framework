# singleflight key 摘要手工指定 + 可插拔算法 — 设计文档

- 日期：2025-08-29
- 状态：已与用户对齐，批准实现
- 模块：`team4u-singleflight`

## 背景与动机

当前最终协调 key 由 `SingleFlightKeys.compose(point, renderedKey, digestThreshold)` 组装：

```
encode(point) + "_" + encode(renderedKey)
```

超过 `digestThreshold`（默认 128）时自动保留 48 字符可读前缀并追加 SHA-256 摘要。该机制的问题：

1. **摘要自动触发，用户无法控制**。部分 point 的业务 key 是敏感信息（手机号、身份证号），不希望明文进入存储。但现有机制只在 key 超长时才摘要，且摘要保留 48 字符可读前缀——短 key（如手机号）完整留在前缀中，等于没有脱敏。
2. **摘要算法硬编码 SHA-256**，无扩展点。低熵标识符可被彩虹表穷举还原，需要的算法（如 HMAC）无法接入。

## 目标 / 非目标

**目标**

- 摘要是否发生由规则显式声明（`keyDigest` 字段），摘要不再是长度触发的自动行为；
- 摘要算法可插拔：命名注册表 + 编程注册，内置一个简单的 `sha256` 算法；
- 用户可用自定义算法（如 HMAC）实现后注册，规则按名引用。

**非目标**

- 不做长度触发的自动摘要——`digestThreshold` 字段与自动摘要逻辑**整体删除**，不做兼容层（用户明确：不用考虑兼容性，直接重构）；
- 不内置 HMAC 等复杂算法；
- 不做 SPI 自动装配（`SingleFlightStores` 也没有，保持仓库惯例一致，YAGNI）。

## 设计

### 规则字段

`SingleFlightRule` 删除 `digestThreshold`，新增：

```java
/**
 * key 摘要算法名（注册于 SingleFlightKeyDigests.global()）。
 * 空白表示不摘要，业务 key 明文进入存储；未注册的名字在规则加载期失败。
 */
private String keyDigest;
```

规则用法：`"keyDigest":"sha256"`。

存量规则 JSON 残留的 `digestThreshold` 字段会被 Jackson 静默忽略（`JacksonSerializerPolicy` 已配置 `FAIL_ON_UNKNOWN_PROPERTIES=false`），无需迁移脚本。

### 扩展接口与注册表

新文件位于 `policy` 包：

```java
public interface SingleFlightKeyDigest extends KeyedPolicy<String> {
    /** 命名，如 "sha256" */
    String key();

    /** 对渲染后的业务 key 做摘要；返回值必须稳定、可直接作为存储 key 的一部分 */
    String digest(String renderedKey);
}

public final class SingleFlightKeyDigests {
    private static final SingleFlightKeyDigests GLOBAL = new SingleFlightKeyDigests();

    private final KeyedPolicyRegistry<String, SingleFlightKeyDigest> registry =
            new KeyedPolicyRegistry<>(SingleFlightKeyDigest.class);

    static {
        GLOBAL.register(new Sha256KeyDigest());
    }

    /** 全局注册表实例 */
    public static SingleFlightKeyDigests global() { return GLOBAL; }

    /** 注册（同名后注册者覆盖先注册者），返回 this 支持链式 */
    public SingleFlightKeyDigests register(SingleFlightKeyDigest digest) { ... }

    /** 按名解析；未注册抛 IllegalArgumentException，由 RuleCompiler 转配置异常 */
    public SingleFlightKeyDigest resolve(String name) { ... }
}
```

要点：

- 直接实现 `KeyedPolicy<String>`（同 `MaskPolicy` 模式），无需 `NamedStore` 式包装类；
- 内置 `Sha256KeyDigest`（`key()="sha256"`）：**全量 SHA-256 hex，不保留可读前缀**——隐私目的决定不能留前缀；
- 自定义算法示例（用户侧代码）：应用启动时 `SingleFlightKeyDigests.global().register(new HmacSha256KeyDigest(secret))`，规则里 `"keyDigest":"hmac-sha256"`。同名后注册覆盖内置，与 `SingleFlightStores` 行为一致。

### key 组装流程变化

```
之前：encode(point) + "_" + encode(renderedKey)，超阈值自动 前缀 + #sha256_摘要
之后：encode(point) + "_" + encode(digest != null ? digest.digest(renderedKey) : renderedKey)
```

- `SingleFlightKeys.compose(point, renderedValue, digest)`：第三参从 `int threshold` 改为 `SingleFlightKeyDigest`（可 null）；
- 删除 `READABLE_PREFIX_LENGTH`、`digest(String, int)`、`sha256Hex()`（SHA-256 逻辑移入 `Sha256KeyDigest`）；
- 摘要输出仍过 `encode()`：hex 全是安全字符是 no-op；自定义算法可能返回非安全字符，统一兜住；
- **point 保持明文**：排查时能看出是哪个规则的窗口；敏感的只是业务 key。

### 编译与校验

`RuleCompiler.compileValidated()`：

- `blank(keyDigest) → null`（不摘要）；
- 否则 `SingleFlightKeyDigests.global().resolve(keyDigest)`，未注册的名字抛 `SingleFlightConfigException`（规则加载期失败、热更新保旧，走现有 `ConfigDrivenRegistry` 语义）；
- 解析出的策略实例存入 `CompiledRule`，运行期零查找开销。

`SingleFlightEngine.renderKey()` 改传策略实例而非阈值。

### 文档与测试

- `docs/singleflight/README.md`：规则字段表删 `digestThreshold` 行、加 `keyDigest` 行；「核心概念」表更新 `SingleFlightKeys` 描述与架构图标注；补一小节「key 摘要与自定义算法」含注册示例；
- `quick-start.md` / `scenarios.md` 无涉及摘要的示例（已核对），不动；
- 测试：
  - `SingleFlightKeysTest` 改造：无摘要原样拼接 / 有摘要全量替换 / 自定义策略生效 / 空白校验；
  - `SingleFlightRuleValidationTest` 补：`keyDigest` 未注册时加载失败；
  - 新增 `SingleFlightKeyDigestsTest`：注册、同名覆盖、resolve 未注册异常、内置 sha256 稳定性。

## 已接受的取舍

- 删除自动摘要后，超长 key 会原样落库。如果将来有 point 拿整段报文做 key，存储侧需自行承受长度（Redis 无所谓；JDBC 表有列宽限制，超长 key 写入会失败并按 `onStoreFailure` 策略处置）。这是选择「彻底移除自动摘要」时已接受的取舍。

## 涉及文件清单

| 动作 | 文件 |
| :--- | :--- |
| 修改 | `config/SingleFlightRule.java`（删 `digestThreshold`，加 `keyDigest`） |
| 修改 | `core/SingleFlightKeys.java`（compose 签名改造，删自动摘要） |
| 新增 | `policy/SingleFlightKeyDigest.java`、`policy/SingleFlightKeyDigests.java`、`policy/Sha256KeyDigest.java` |
| 修改 | `core/RuleCompiler.java`（编译期解析命名摘要） |
| 修改 | `core/SingleFlightEngine.java`（renderKey 传策略） |
| 修改 | `core/CompiledRule.java`（存策略实例） |
| 修改 | 测试：`SingleFlightKeysTest`、`SingleFlightRuleValidationTest`，新增 `SingleFlightKeyDigestsTest` |
| 修改 | `docs/singleflight/README.md` |
