# 状态机组件 (team4u-fsm)

# 背景

在电商订单履约、工单审批、支付流转、优惠券生命周期管理等场景中，业务实体通常经历一系列离散的状态变迁。传统的实现方式往往存在以下痛点：

- **`if-else` / `switch-case` 逻辑泛滥**：状态校验、分支判断、业务更新与事件通知混杂在 Service 方法中，新增状态极易引发回归故障；
- **缺乏编译期与拓扑防呆校验**：死规则、不可达迁移与未闭合的边无法在启动期被发现，通常直到线上触发非法流转才暴露；
- **状态图与代码脱节**：系统架构图停留在 Wiki 文档中，随着业务迭代迅速腐化，代码与设计严重不一致。

`team4u-fsm` 是一个专为 Java 业务系统设计的强类型、轻量级有限状态机（Finite State Machine）组件。它提供了流畅的声明式 DSL、分层匹配因果律、严格的构建期拓扑校验与原生的 Mermaid 状态图生成能力。

---

# 核心特性

- **流畅的类型安全 DSL** ：通过 `StateMachine.builder()` 声明式定义 `from().on().when().to().action()`，严格约束状态、事件与业务上下文泛型；
- **四层匹配因果律**：支持精确匹配、单边通配（`fromAny()` / `onAny()`）与全局通配兜底，分层优先级清晰严密；
- **构建期静态拓扑校验**：自动拦截未闭合迁移、空规则集合以及同桶内不可达死规则（Dead-rule Defense）；
- **原生 Mermaid 图表导出**：通过 `StateMachineMermaid` 一键将不可变状态机实例渲染为标准 Mermaid 状态图；
- **零运行时外部依赖**：纯 JDK 实现，内存占用极小，单例执行无锁并发安全。

---

## 模块坐标

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-fsm</artifactId>
</dependency>
```

---

## 章节导航与专题专栏

- [快速开始](quick-start.md)：5 分钟掌握状态机构建与事件流转。
- [状态机定义与构建器 API](fsm-builder.md)：来源/目标配置、单边与全局通配、四层匹配优先级机制。
- [流转契约：Guard、Action 与 Context](fsm-transition.md)：条件守卫、副作用动作与业务上下文载荷传递。
- [流转语义与生命周期模型](fsm-semantics.md)：流转因果律、不可达规则防御与状态迁移生命周期。
- [Mermaid 状态机图表导出与可视化](fsm-mermaid.md)：自动生成 `stateDiagram-v2` 源码与文档集成。
- [结果模型与异常诊断体系](fsm-diagnostics.md)：`TransitionResult` 三态模型与 `StateMachineException` 异常分类。
- [企业工单与审批流实战案例](fsm-sample.md)：多级员工请假审批、跨级流转与全局取消实战。
