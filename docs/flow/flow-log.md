# 流程结构化日志与执行树 (team4u-flow-log)

# 背景

在分布式长事务与微服务业务流程编排中，全链路可视化排障与运行时状态监控至关重要。常规的流程日志记录通常面临以下痛点：

- **日志信息扁平无序**：单纯按时间顺序打印的单行日志无法清晰呈现复杂流程（分支路由、并行汇聚、嵌套子流程、重试治理）的调用栈与拓扑层级关系；
- **敏感信息明文泄漏**：业务流转上下文（如用户手机号、身份证、银行卡号、密码等）在记录日志时若缺乏统一的脱敏约束，极易引发合规与数据安全风险；
- **全量打印与大对象污染**：业务上下文 DTO 往往包含大量内部缓存、临时变量或超长报文，如果不加选择全量序列化，将导致日志文件急剧膨胀并消耗大量 I/O；
- **单步日志与最终汇总格式割裂**：执行过程中的中间状态与流程结束时的汇总结果格式不一致，增加排障与解析成本。

`team4u-flow-log` 是建立在 `team4u-flow`、`team4u-log` 与 `team4u-mask` 之上的结构化日志与执行树观察者模块，提供实时单步结构化日志输出、类与字段级属性挑选、源码与无源码动态安全脱敏、以及终态 ASCII 执行树汇总。

---

# 架构生态与关联组件协同

`team4u-flow-log` 采用松耦合的组件协同架构，在流程生命周期的关键节点实现全自动、零侵入的观测与脱敏：

```mermaid
graph TD
    subgraph "team4u-flow (流程编排引擎)"
        ENG["LocalExecutable / SerialMachine"] --> EVT["FlowObserver.Event<br/>(原生携带当前步骤入参 / 出参 payload)"]
    end

    subgraph "team4u-flow-log (日志与执行树)"
        EVT --> FLO["FlowLoggingObserver"]
        FLO --> PROJ["ContextProjector<br/>(TypeRouting / Annotated / Fields / Lambda)"]
        PROJ --> FMT["ContextFormatter (统一格式化管道)"]
        FMT --> STEP["单步实时日志 (Loggers 结构化输出)"]
        FMT --> TREE["TraceTreeFormatter (终态 ASCII 树汇总)"]
    end

    subgraph "team4u-mask (安全脱敏引擎)"
        PROJ -.-> MASK["MaskedJson / FastMasker<br/>(Jackson 序列化器修饰与内置算法)"]
        PROJ -.-> REPO["MaskRuleRepository<br/>(动态规则仓库 / 配置中心热更新)"]
    end

    subgraph "team4u-log (结构化日志引擎)"
        STEP --> LOG["日志输出 (LogEvent / Slf4j / ELK)"]
        TREE --> LOG
    end
```

### 关联组件职责矩阵

| 关联组件 | 模块坐标 | 核心职责 |
| :--- | :--- | :--- |
| **`team4u-flow`** | `com.team4u:team4u-flow` | **流程状态机与事件广播**：在流程执行中发布包含节点路径 `path`、耗时、四态 `Outcome` 与数据载荷 `payload` 的原始事件。 |
| **`team4u-log`** | `com.team4u:team4u-log` | **统一结构化日志**：提供 `Loggers.of(...)` 链式构建器与 `LogEvent` 标准日志模型。 |
| **`team4u-mask`** | `com.team4u:team4u-mask` | **敏感数据安全掩码**：提供基于 Jackson 序列化修饰符的动态脱敏，以及脱敏策略规则仓库。 |
| **`team4u-base`** | `com.team4u:team4u-base` | **通用底层工具**：提供带缓存的高性能反射 `ReflectUtil` 与注解继承查找 `AnnotationUtil`。 |

---

# 引入依赖

在 `pom.xml` 中引入 `team4u-flow-log` 模块：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-flow-log</artifactId>
</dependency>
```

---

# 快速上手

```java
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.log.FlowLoggingObserver;
import com.team4u.framework.flow.model.FlowResult;

// 1. 构建日志观察者
FlowLoggingObserver observer = FlowLoggingObserver.builder()
        .loggerNamePrefix("flow.trace") // 自定义 Logger 前缀
        .printStepLogs(true)            // 开启运行中单步实时日志
        .printTreeSummary(true)         // 开启流程结束时的 ASCII 执行树与最终上下文汇总
        .build();

// 2. 编译流程并装配观察者
LocalExecutable<OrderContext, OrderContext> executable = Local.from(orderFlow)
        .flowId("order-checkout")
        .flowVersion(1)
        .resolver(beanResolver)
        .observer(observer)
        .compile();

// 3. 执行流程
OrderContext context = new OrderContext();
FlowResult<OrderContext> result = executable.run(context);
```

---

# 属性挑选与投影策略 (`ContextProjector`)

`ContextProjector` 负责在日志记录前对上下文对象进行字段裁剪与转换，支持多种投影策略：

### 注解驱动模式（默认）

通过 `@TraceContext` 与 `@TraceIgnore` 声明字段挑选规则：

```java
FlowLoggingObserver observer = FlowLoggingObserver.builder()
        .contextProjector(ContextProjector.annotated())
        .build();
```

### 强类型函数式投影模式

通过 Lambda 表达式进行字段挑选、别名重命名与派生属性计算：

```java
FlowLoggingObserver observer = FlowLoggingObserver.builder()
        .contextProjector(ContextProjector.of((OrderContext ctx) -> Map.of(
                "orderId", ctx.getOrderId(),
                "mobile", ctx.getMobile(),
                "status", ctx.getStatus()
        )))
        .build();
```

### 属性白名单模式

指定输出字段名称白名单，自动提取对应属性：

```java
FlowLoggingObserver observer = FlowLoggingObserver.builder()
        .contextProjector(ContextProjector.fields("orderId", "amount", "mobile"))
        .build();
```

### 多类型路由模式（异构 DTO 流水线）

针对多步骤流转异构 DTO（不同步骤类型不同）的场景，可通过 `byType()` 针对每个类型独立配置投影规则，支持融入 `of` 自定义函数、白名单与继承匹配：

```java
ContextProjector typeProjector = ContextProjector.byType()
        // 1. 融入 of 函数式自定义值与重命名能力 (强类型)
        .bind(UserVerifyReq.class, (UserVerifyReq req) -> Map.of(
                "uid", req.getUserId(),
                "displayTag", "VIP-" + req.getLevel()
        ))
        // 2. 绑定特定类型的字段白名单
        .bindFields(PaymentOrderDTO.class, "orderId", "amount", "cardNo")
        // 3. 兜底回退模式（未注册类回退到注解模式，或传 null 原样透传）
        .fallback(ContextProjector.annotated())
        .build();

FlowLoggingObserver observer = FlowLoggingObserver.builder()
        .contextProjector(typeProjector)
        .build();
```

---

# 数据安全脱敏治理

框架同时支持**有源码注解声明**与**无源码动态脱敏**两种模式：

### 模式 A：源码注解声明脱敏

当拥有 DTO 源码时，直接在类与字段上标注注解：

| 注解 | 作用目标 | 核心语义 |
| :--- | :--- | :--- |
| **`@TraceContext`** | 类（`TYPE`） / 字段（`FIELD`） | • 标注在类上：该类所有业务字段默认输出至日志；<br/>• 标注在字段上：白名单输出该字段，支持别名。 |
| **`@TraceIgnore`** | 字段（`FIELD`） / 方法（`METHOD`） | 当类标注了 `@TraceContext` 时，显式排除该字段。 |
| **`@Mask(MaskType)`** | 字段（`FIELD`） | 对输出的敏感字段自动应用安全掩码（手机、身份证、银行卡等）。 |

```java
package com.example.order;

import com.team4u.framework.flow.log.TraceContext;
import com.team4u.framework.flow.log.TraceIgnore;
import com.team4u.framework.mask.Mask;
import com.team4u.framework.mask.MaskType;
import lombok.Data;

import java.util.Map;

@Data
@TraceContext // 1. 类级别声明：默认包含所有业务字段
public class OrderContext {

    private String orderId;

    @Mask(MaskType.NAME) // 姓名脱敏：张*丰
    private String realName;

    @Mask(MaskType.MOBILE) // 手机号脱敏：138*****000
    private String mobile;

    @Mask(MaskType.ID_CARD_NO) // 身份证脱敏：11010***********45
    private String idCardNo;

    @Mask(MaskType.BANK_CARD_NO) // 银行卡脱敏：622202******1234
    private String cardNo;

    private Double amount;
    private String status;

    @TraceIgnore // 2. 显式排除内部缓存，不污染日志
    private Map<String, Object> internalCache;

    @TraceIgnore // 3. 显式排除超大二进制报文
    private byte[] rawImagePayload;
}
```

### 模式 B：无源码场景动态脱敏

针对第三方 SDK、外部二方 Jar 包或代码生成器产出的只读 DTO，提供以下动态脱敏途径：

#### 途径 1：函数式 Lambda 投影 + `FastMasker`

在编排层直接调用 `FastMasker` 工具进行动态脱敏：

```java
import com.team4u.framework.mask.FastMasker;

ContextProjector projector = ContextProjector.byType()
        .bind(ThirdPartyOrderDTO.class, (ThirdPartyOrderDTO dto) -> Map.of(
                "orderId", dto.getOrderId(),
                "mobile", FastMasker.mobile(dto.getPhone()),          // 动态手机号脱敏
                "idCard", FastMasker.idCardNo(dto.getIdCard()),       // 动态身份证脱敏
                "cardNo", FastMasker.bankCardNo(dto.getCardNumber()), // 动态银行卡脱敏
                "amount", dto.getAmount()
        ))
        .build();
```

#### 途径 2：通过 `MaskRuleRepository` 动态注册

利用 `team4u-mask` 的规则仓库，按类全限定名或通配符 `*` 动态注册脱敏规则：

```java
import com.team4u.framework.mask.config.MaskRuleRepository;

Map<String, Map<String, String>> rules = new HashMap<>();

// 1. 按第三方类全限定名绑定
rules.put("com.external.sdk.ThirdPartyOrderDTO", Map.of(
        "phone", "MOBILE",
        "cardNumber", "BANK_CARD_NO"
));

// 2. 或使用全局字段通配符（所有类的这些字段自动脱敏）
rules.put("*", Map.of(
        "mobile", "MOBILE",
        "phone", "MOBILE",
        "idCard", "ID_CARD_NO"
));

MaskRuleRepository.getInstance().setRuleCache(rules);
```

#### 途径 3：外部配置中心热更新

在配置中心维护 JSON 键 `team4u.mask.rules`，支持生产环境免重启秒级热生效：

```json
{
  "com.external.sdk.ThirdPartyOrderDTO": {
    "phone": "MOBILE",
    "cardNumber": "BANK_CARD_NO"
  },
  "*": {
    "mobile": "MOBILE",
    "password": "PASSWORD"
  }
}
```

---

# 节点名称 (Label) 缺省回退展示机制

若在编排 DSL 时未显式通过 `.named("xxx")` 设置节点名称，`FlowLoggingObserver` 会依据 AST 描述符按以下优先级自动提取最直观的可读名称：

| 优先级 | 判定条件 | 示例场景 | 默认展示名称 |
| :--- | :--- | :--- | :--- |
| **1. 显式指定** | 显式调用 `.named("xxx")` | `Flow.step(...).named("用户验签")` | `"用户验签"` |
| **2. 绑定的实现类** | 绑定了非 Lambda 的具体 Class | `Flow.step(ValidateOperation.class)` | `"ValidateOperation"`（若有 Spring 限定符则形如 `"ValidateOperation (vip)"`） |
| **3. 绑定的契约接口** | 绑定了契约接口 Class | `Flow.step(PaymentOperation.class)` | `"PaymentOperation"` |
| **4. 节点拓扑种类** | Lambda 匿名步骤或结构节点 | `Flow.step((ctx, req) -> ...)` 或 `Flow.parallel(...)` | 节点类型枚举名：`"INVOKE"`、`"PARALLEL"`、`"ROUTE"`、`"CONTROL"` 等 |
| **5. 顶层流程** | 流程根节点 | 顶层 `LocalExecutable` | `"flow: <flowId>"` |
| **6. 极端兜底** | 描述符信息缺失 | 外部未定义节点 | `"<unnamed>"` |

---

# 链路树模型编程式获取与断言 (`TraceNode`)

除输出控制台与日志系统外，`FlowLoggingObserver` 还支持通过编程式 API 直接提取结构化的调用拓扑树模型，用于下游 APM 指标上报或单元测试断言：

```java
// 获取最近一次已完成执行的根追踪节点
TraceNode rootNode = observer.rootTraceNode();

// 或按 executionId 获取指定执行实例的根节点 (执行期活跃态)
TraceNode targetNode = observer.rootTraceNode("ORD-10086");

// 访问节点核心指标
String path = rootNode.getPath();            // AST 节点路径，如 "$"、"$/0"
String label = rootNode.getLabel();          // 节点显示标签
long duration = rootNode.getDurationMs();    // 耗时毫秒
String outcome = rootNode.getOutcome();      // 四态执行结果 (ACCEPTED / REJECTED / SKIPPED / FAILED)
String extra = rootNode.getExtra();          // 附加元数据 (如 attempt=2, selected=case:0)

// 获取线程安全的子节点快照
List<TraceNode> children = rootNode.snapshotChildren();
```

### 并发安全与内存自闭环回收

- **Per-Execution 槽位隔离**：`FlowLoggingObserver` 内部使用 `ConcurrentHashMap<String, ExecutionTrace>` 按 `executionId` 进行隔离。多个线程并发复用同一个编译好的 `LocalExecutable` 时，各自的链路追踪树互不干扰；
- **自闭环回收防泄漏**：当流程执行完毕（`FLOW_COMPLETED` / `FLOW_CANCELLED` / `FLOW_SUSPENDED`）时，当前执行树会自动从活跃跟踪表中弹出，保障高并发吞吐下的内存即时回收。

---

# 完整日志输出样式

### 运行中单步实时日志（逐行流式输出）

```text
[INFO] flow.trace.order-checkout | action=FLOW_STARTED | status=start | execId=ORD-10086 | context={"orderId":"ORD-10086","realName":"张*丰","mobile":"138*****000","idCardNo":"11010***********45","cardNo":"622202******1234","amount":299.0,"status":"INITIAL"}
[INFO] flow.trace.order-checkout | action=NODE_STARTED | status=start | execId=ORD-10086 | path=$/0 | label=用户实名核验 | context={"orderId":"ORD-10086","realName":"张*丰","mobile":"138*****000","idCardNo":"11010***********45","cardNo":"622202******1234","amount":299.0,"status":"INITIAL"}
[INFO] flow.trace.order-checkout | action=NODE_COMPLETED | status=success | execId=ORD-10086 | path=$/0 | label=用户实名核验 | duration=12ms | outcome=ACCEPTED | context={"orderId":"ORD-10086","realName":"张*丰","mobile":"138*****000","idCardNo":"11010***********45","cardNo":"622202******1234","amount":299.0,"status":"VERIFIED"}
[INFO] flow.trace.order-checkout | action=NODE_STARTED | status=start | execId=ORD-10086 | path=$/1 | label=银行扣款 | context={"orderId":"ORD-10086","realName":"张*丰","mobile":"138*****000","idCardNo":"11010***********45","cardNo":"622202******1234","amount":299.0,"status":"VERIFIED"}
[INFO] flow.trace.order-checkout | action=NODE_COMPLETED | status=success | execId=ORD-10086 | path=$/1 | label=银行扣款 | duration=85ms | outcome=ACCEPTED | attempt=2 | context={"orderId":"ORD-10086","realName":"张*丰","mobile":"138*****000","idCardNo":"11010***********45","cardNo":"622202******1234","amount":299.0,"status":"PAID"}
```

### 流程结束终态执行树与最终上下文汇总

```text
[INFO] Flow Execution Summary [flowId=order-checkout | execId=ORD-10086 | total=125ms | outcome=ACCEPTED]
└── [$] flow: order-checkout (125ms) [ACCEPTED]
    ├── [$/0] 用户实名核验 (12ms) [ACCEPTED]
    ├── [$/1] 支付渠道路由 (3ms) [ACCEPTED] selected=online
    │   └── [$/1/body] 重试治理控制器 (85ms) [ACCEPTED]
    │       └── [$/1/body/0] 银行扣款 (82ms) [ACCEPTED] attempt=2
    └── [$/2] 资源并行结算 (25ms) [ACCEPTED]
        ├── [$/2/branch:0] 库存预占 (18ms) [ACCEPTED]
        └── [$/2/branch:1] 电子发票开具 (22ms) [ACCEPTED]

Final Context:
{
  "orderId": "ORD-10086",
  "realName": "张*丰",
  "mobile": "138*****000",
  "idCardNo": "11010***********45",
  "cardNo": "622202******1234",
  "amount": 299.00,
  "status": "PAID"
}
```
