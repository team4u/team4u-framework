# 状态机组件 (team4u-fsm)

# 背景

订单、审批、工单这类业务天然由“状态 + 事件 + 流转”驱动。常见的实现方式有几种，各有代价：

- **硬编码 if-else**：流转规则散落在各个 Service 方法里，改一条规则要通读全部分支，无法回答“当前状态到底能去哪些状态”。
- **通用流程引擎**：把状态机寄生在重量级流程引擎上，规则要拆成引擎的节点模型，状态、事件、动作全部退化为 `Object` 与字符串引用，丢失全部类型检查。
- **表驱动 + 反射**：规则存进数据库或 JSON，运行时按字符串找 Spring Bean 执行动作。看似灵活，实际把编译期错误推迟到了线上。

`team4u-fsm` 是一个纯 Java 8、零运行时依赖的强类型有限状态机组件。它只做一件事：**把流转规则声明成不可变的定义，然后由一个无状态、不可变的迁移引擎执行一次迁移判定**。

---

# 设计

## 核心模型

组件围绕三个泛型参数建模：状态 `S`、事件 `E`、业务上下文 `C`。推荐 `S`/`E` 使用枚举，`C` 使用业务对象。

```
StateMachine<S, E, C>          不可变、无状态的迁移定义（构建后冻结）
StateMachineBuilder<S, E, C>   启动期构建 DSL，产出上述定义
Transition<S, E, C>            一条迁移规则：来源 + 事件 + 守卫 + 目标 + 动作
TransitionGuard<S, E, C>       守卫：只判定，不产生副作用
TransitionAction<S, E, C>      动作：迁移附带业务逻辑，不可改写目标状态
TransitionContext<S, E, C>     守卫与动作共享的不可变迁移上下文
TransitionResult<S, E, C>      一次触发的完整执行结果
TransitionOutcome              TRANSITIONED / NO_TRANSITION / GUARD_REJECTED
StateMachineMermaid            渲染 stateDiagram-v2 状态图
```

## 三条设计底线

**1. 定义不可变、实例无状态。** 状态机不保存业务对象的当前状态。调用方传入 `(当前状态, 事件, 上下文)`，引擎判定并执行动作后返回 `TransitionResult`，由调用方决定如何更新实体、落库或持久化。因此同一实例可被多线程并发复用，也不会与数据库事务、容器生命周期产生耦合。注意：引擎的路由与判定不依赖任何隐藏状态，但守卫与动作由调用方实现，可能修改业务上下文或产生外部副作用，因此不要把一次 `fire`/`tryFire` 当作纯函数调用对待。

**2. 目标状态在构建期固定。** 动作不能改写去向；需要按条件分流时，必须显式声明多条带守卫的迁移。这样代码、构建期校验和状态图三者表达的永远是同一件事。

**3. 零运行时依赖。** 模块通过 Maven Enforcer 强制约束：compile/runtime 作用域不允许任何依赖，只有 JDK 与测试作用域的 JUnit。

## 设计边界

核心只负责单次迁移判定与动作执行，所有状态持有、事务、持久化和外部集成都由调用方决定。规则通过强类型 DSL 在启动期定义，构建完成后冻结；需要动态规则时，可在核心之外实现配置解析器，将配置转换为同一套构建 API。

## 刻意不放进核心的能力

核心保持为一个判定引擎，以下能力全部留给调用方，需要时在外部自行包装：

- **不集成流程引擎（Flow）**：状态机只管单次迁移判定，不管编排、并行、会签。
- **不集成 Spring**：没有自动装配、没有 Bean 名称反射、没有注解扫描。
- **没有全局单例注册表**：状态机实例由调用方创建和持有，想做成单例是调用方的选择。
- **不做动态规则解析**：不从 JSON / 数据库 / 配置中心加载规则。规则是代码，改规则走发布流程，换来编译期检查。
- **没有事件总线**：不发布领域事件。需要通知下游时，在动作里调用调用方自己的发布设施，或者消费 `TransitionResult`。
- **不管理持久化与事务**：引擎不碰数据库。`fire`/`tryFire` 返回结果后，由调用方在自己的事务边界内更新状态、发布 `TransitionResult` 或接入审计/消息适配器。

---

# 匹配语义（速览）

迁移按“具体度”分三层，层间顺序固定，层内按声明顺序取第一条守卫通过的规则：

1. 精确状态 + 精确事件；
2. 单边通配：精确状态 + 任意事件、任意状态 + 精确事件，两桶按声明顺序归并；
3. 任意状态 + 任意事件，作为最后的全局兜底。

精确规则（精确状态 + 精确事件）永远不会被更早声明的通配规则遮蔽。守卫或动作抛出异常时快速失败，不再尝试任何后续候选规则。完整规则、例外与设计动机见[语义手册](fsm-semantics.md)。

---

# 结果模型

`fire` 与 `tryFire` 的区别只在“拒绝时抛不抛异常”：

| 方法 | 无候选规则 | 守卫全部拒绝 | 守卫/动作抛异常 |
| :--- | :--- | :--- | :--- |
| `fire` | 抛 `TransitionRejectedException` | 抛 `TransitionRejectedException` | 抛 `TransitionExecutionException` |
| `tryFire` | 返回 `NO_TRANSITION` 结果 | 返回 `GUARD_REJECTED` 结果 | 抛 `TransitionExecutionException` |

`TransitionResult` 携带机器标识、来源/目标状态、事件、命中的迁移定义与本次判定评估过的候选迁移数量（含守卫未通过的规则与不带守卫的无条件规则）；`getState()` 返回执行后的有效状态（迁移成功为目标状态，否则为来源状态），可直接写回业务对象。

---

# 快速上手

```java
StateMachine<OrderState, OrderEvent, Order> machine = StateMachine
        .<OrderState, OrderEvent, Order>builder("order", OrderState.CREATED)
        .from(OrderState.CREATED).on(OrderEvent.SUBMIT).to(OrderState.SUBMITTED)
            .named("created-submit")
            .action(ctx -> audit(ctx))
        .fromAny().on(OrderEvent.CANCEL)
            .when("not shipped", ctx -> !shipped(ctx))
            .to(OrderState.CANCELLED)
        .build();
```

完整可运行示例见[快速开始](quick-start.md)。

---

# 适用边界

- 适合：单实体的状态流转建模（订单、审批、工单、支付单），规则在发布期确定、追求类型安全与可预测匹配的场景。
- 不适合：需要动态下发规则的场景（可自行在外层做规则翻译成 DSL）；需要长流程编排、并行网关的场景（那是流程引擎的职责）。
