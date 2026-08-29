# key 摘要与自定义算法

业务 key 是敏感信息（手机号、证件号、卡号）时，可以配置摘要算法，让业务 key 以摘要值进入存储，避免明文落库。

---

## 启用摘要

规则加 `keyDigest` 字段，按名引用注册于 `SingleFlightKeyDigests.global()` 的摘要算法：

```properties
team4u.singleflight.user.risk={"id":"user.risk","key":"${idNumber}","cacheTtlMillis":60000,"keyDigest":"sha256"}
```

执行后存储里 lock / session / cache 三个 space 的最终 key 形如：

```text
user.risk_4a1d0eaeeb3f7d58a1e16cfafcbf5eac5b6e32c0db9a5b2e5ee6b0a5cbab1f0f
```

- 摘要作用于渲染后的业务 key，**全量替换**，point 保持明文——排查问题时仍能从 key 看出是哪个规则的执行窗口；
- `keyDigest` 留空（或省略）即不摘要，业务 key 明文进入存储；
- 引用了未注册的算法名，规则加载失败（配置键存在但不可解析，热更新保留旧规则）。

## 内置算法

| 名字 | 算法 | 说明 |
| :--- | :--- | :--- |
| `sha256` | SHA-256 hex（64 字符） | 全量摘要业务 key |

注意：SHA-256 对低熵标识符不具备抗穷举能力——有存储读权限的人可以拿手机号段跑彩虹表还原明文。有此合规要求请使用自定义 HMAC 算法（密钥放配置中心，不落存储）。

## 自定义算法

所有摘要算法实现 `SingleFlightKeyDigest` 接口（继承自 `KeyedPolicy<String>`）。

### 步骤 1：编写算法实现类

```java
import com.team4u.framework.singleflight.policy.SingleFlightKeyDigest;
import java.nio.charset.StandardCharsets;

public class HmacSha256KeyDigest implements SingleFlightKeyDigest {

    private final byte[] secret;

    public HmacSha256KeyDigest(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String key() {
        return "hmac-sha256"; // 算法唯一路由标识，规则里 keyDigest 按此引用
    }

    @Override
    public String digest(String renderedKey) {
        // HMAC-SHA256 hex
        return hmacSha256Hex(secret, renderedKey);
    }
}
```

### 步骤 2：应用启动时注册

```java
import com.team4u.framework.singleflight.policy.SingleFlightKeyDigests;

SingleFlightKeyDigests.global().register(new HmacSha256KeyDigest(secret));
```

- 同名后注册者覆盖先注册者——可以覆盖内置的 `sha256`，注册表行为与 `SingleFlightStores` 一致；
- `register` 返回 `this`，支持链式注册多个算法。

### 步骤 3：规则按名引用

```properties
team4u.singleflight.user.risk={"id":"user.risk","key":"${idNumber}","cacheTtlMillis":60000,"keyDigest":"hmac-sha256"}
```

## 实现约束

- `digest` 必须是**纯函数**：同一输入永远返回同一输出。否则同 key 的并发调用会散落到不同执行窗口，合并失效；
- 返回值必须稳定且非空白——空串、空白在 key 组装期被拒绝（`IllegalArgumentException`）；
- 摘要发生在百分号编码之前：实现只需返回算法输出，非法字符由 `SingleFlightKeys` 统一转义，无需关心存储层 key 约束；
- 换算法（或换 HMAC 密钥）等于换 key 语义：进行中的执行窗口不会迁移，切换瞬间的并发调用会落到新窗口。

## 超长 key

存储 key 长度随业务值自然增长，由业务侧自行评估：拿整段报文做 key 时，Redis 无长度顾虑，JDBC 表需确认列宽（超长写入失败按 `onStoreFailure` 策略处置，`FAIL_CLOSED` 抛配置异常、`PASS_THROUGH` 记 warn 后直接执行 loader）。
