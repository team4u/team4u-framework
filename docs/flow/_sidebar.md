* [组件概览](README.md)
* [快速开始](quick-start.md)

* **外部定义与文本 DSL**
  * [外部流程定义与符号注册 (team4u-flow-definition)](flow-definition.md)
  * [文本 DSL 语法与统一门面 (team4u-flow-dsl)](flow-dsl.md)

* **核心语义与模型**
  * [四态业务结果与生命周期模型](flow-outcome.md)
  * [四态传播规则与消费机制](flow-propagation.md)
  * [运行时节点与 DSL 编排原语](flow-nodes.md)
  * [核心语义全景总览](flow-semantics.md)

* **控制与治理**
  * [流程治理概览：Policy、Retry 与 Timeout](flow-governance.md)
  * [限流治理策略 (team4u-flow-ratelimiter)](policy-ratelimiter.md)
  * [重试与退避治理策略 (team4u-flow-retry)](policy-retry.md)
  * [表达式规则门控策略 (team4u-flow-criterion)](policy-criterion.md)
  * [自定义治理策略开发指南](policy-custom.md)
  * [并行分支与汇合治理](flow-parallel.md)
  * [挂起续接与协作式取消合同](flow-suspend.md)
  * [Local 线程模型与死锁防御](flow-threading.md)

* **容器与持久化**
  * [Bean 容器集成与 Spring 治理](flow-bean.md)
  * [Durable 状态机与 CAS 检查点](flow-durable-core.md)
  * [Durable 两段式恢复与 PersistentPolicy](flow-durable-resume.md)
  * [快照存储结构与 StateMapper 编解码](flow-durable-snapshot.md)
  * [DurableStore 存储 SPI 与 KV 适配](flow-durable-kv.md)
  * [Durable 持久化全景总览](flow-durable.md)

* **运维与工程化**
  * [流程结构化日志与执行树 (team4u-flow-log)](flow-log.md)
  * [可视化图表渲染与双投影架构](flow-diagram.md)
  * [测试支持与测试套件](flow-test.md)
  * [诊断码体系与故障排查手册](flow-diagnostics.md)
  * [扩展机制与 SPI 开发指南](flow-extension.md)
  * [实战案例库与生产模式](flow-sample.md)
