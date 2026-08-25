# 动态治理与 FinOps 成本保护

`team4u-log` 将日志治理从“代码硬编码”提升为“**配置驱动与组件自治**”。无需重启应用，即可在线调整染色范围、脱敏规则与成本保护阈值。

---

## 1. 动态条件染色提权 (`team4u.log.dyeing`)

线上排查特定 VIP 客户、特定订单或偶发错误时，无需将全局日志级别调至 DEBUG。只需下发一条染色规则，符合条件的日志将自动被提权输出。

- **配置 Key**：`team4u.log.dyeing`
- **表达式引擎**：基于 `team4u-criterion` 语法

### 配置结构 (`DyeingRule`)
```json
[
  {
    "id": "vip_user_debug",
    "condition": "userId == 'U1001' || tenantId == 'TENANT_VIP'",
    "targetLevel": "DEBUG"
  },
  {
    "id": "slow_order_trace",
    "condition": "meta_action == 'CreateOrder' && meta_durationMs > 200",
    "targetLevel": "WARN"
  }
]
```

### 上下文变量取值源 (`LogContextCollector` 优先级)
染色匹配采用 Pull 模型，按需延迟寻值，支持以下多源变量：

| 优先级 | 寻值源类别 | 变量名与说明 |
| :--- | :--- | :--- |
| **-200** | **业务载荷源 (`PayloadSource`)** | 访问 `payload` 中的任意业务 KV（如 `userId`, `orderId`），或通过 `payload` 关键字获取整 Map |
| **-100** | **基础元数据源 (`BasicMetadataSource`)** | 访问 `meta_action`, `meta_level`, `meta_logger`, `meta_thread`, `meta_status`, `meta_durationMs` |
| **-90** | **MDC 寻值源 (`MdcSource`)** | 访问 SLF4J MDC 中的任意属性（如 `traceId`, `clientIp`） |
| **SPI** | **自定义扩展源 (`LogContextSource`)** | 可通过 `META-INF/services` 扩展外部寻值源 |

> [!NOTE]
> 当命中染色规则后：
> 1. `event.level` 被重写为规则指定的 `targetLevel`；
> 2. `payload.dyeingRuleMatched` 会自动记录命中的规则 `id`（例如 `"dyeingRuleMatched": "vip_user_debug"`），方便后续排查。

---

## 2. FinOps 成本保护与限流 (`team4u.log.finops`)

在分布式系统中，突发流量、大报文或死循环报错极易导致日志存储成本失控。`team4u-log` 提供了多维度的 FinOps 成本防线。

- **配置 Key**：`team4u.log.finops`

### 配置结构与字段说明 (`FinOpsConfig`)
```json
{
  "maxLogLength": 5000,
  "maxStringLength": 2000,
  "errorLimitPerSecond": 10
}
```

| 字段名 | 默认值 | 作用与截断保护机制 |
| :--- | :--- | :--- |
| `maxLogLength` | `5000` | 整条日志 JSON 最大允许字符数。超过时以 `"... [Truncated at 5000]"` 截断 |
| `maxStringLength` | `2000` | 单个 String 字段的最大允许字符数。序列化器在写入缓冲区前自动截断为 `"... [Truncated len:N]"` |
| `errorLimitPerSecond` | `10` | 每秒同类异常的最大允许输出次数。超过时自动抑制输出，防止异常堆栈打爆磁盘 |

### 成本防爆四大杀手锏：
1. **单个字符串截断 (`TruncatingStringSerializer`)**：
   在 Jackson 序列化字符串时直接截断超长内容，避免 Jackson 分配巨大底层字符缓冲区的内存开销。
2. **字节数组防爆 (`ByteArrayLogSerializer`)**：
   当 `payload` 中不慎传入文件或图片等 `byte[]` 数据时，不进行 Base64 编码展开，而是直接输出 `"[byte[] size: N bytes]"`。
3. **整条日志兜底截断 (`JacksonLogSerializer`)**：
   防止字段过多导致整条 JSON 膨胀，确保单条日志体积严格受控。
4. **异常频控与限流 (`RateLimitInterceptor`)**：
   以 `loggerName + "|" + action + "|" + exceptionClass` 为特征签名，利用 1 秒滑动窗口 (`TimedCache`) 统计频次。超出 `errorLimitPerSecond` 阈值时将 `event.suppressed` 标为 `true` 并不予输出。在首次超出阈值时通过 `TEAM4U-LOG-LIMITER` 记录单次告警。

---

## 3. 动态数据脱敏 (`team4u.mask.rules`)

`team4u-log` 与 `team4u-mask` 深度联动。在 Jackson 序列化输出阶段，自动应用配置中心下发的动态脱敏规则：

```json
{
  "*": {
    "mobile": "MOBILE",
    "idCard": "ID_CARD",
    "password": "PASSWORD",
    "email": "EMAIL"
  }
}
```

任何写入 `Loggers.put("mobile", "13812345678")` 或通过 `@AutoLogTrace` 记录的方法入参，都会自动呈现为 `138*****678`。
