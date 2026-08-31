# Team4u JMH 基准测试

这是一个独立的、不对外发布的 Maven 基准测试工程。它从根 POM 继承 Java 8 编译器与发布设置，但有意不在根 POM 的 `<modules>` 列表中聚合，也不作为构件发布。

---

## 覆盖的核心热路径

- `CriterionMatchBenchmark`：测试两条路径：针对复用 Map `MatchContext` 的预编译逻辑/属性表达式（`age >= 18 && status == 'ACTIVE'`），以及针对复用数值 `MatchContext` 的预编译主体数值表达式（`it > 18`）。测试方法仅执行 `MatchPredicate.test`。
- `RouterRouteBenchmark`：基于预构建 `MatchContext` 进行预构建 `ExpressionRouter` / `RoutePolicy` 路由决策；被测方法中不执行策略解析。
- `KvTieredReadBenchmark`：针对稳定、未过期键与记录的 `TieredStore` L1 命中读取。Setup 阶段的写穿透预热 L1，不执行读时回填；计数的 L2 在 Setup 与被测运行期间保持 0 次 `get` 调用。用例级 `@TearDown` 在关闭 Store 前重新断言 `l2.getCalls == 0`，证明整个预热与所有测量迭代均未访问 L2。
- `ProxyDelegateBenchmark`：预构建的 JDK 接口代理向目标对象委托调用一个无参方法（不涉及 ByteBuddy）。

所有 Benchmark 类均使用 `@State(Scope.Thread)`、平均时间模式（AverageTime）、纳秒输出（`ns/op`）、1 个 Fork、3 轮 1 秒预热以及 5 轮 1 秒测量。Setup 包含所有编译、构建、上下文创建、预热读取与行为断言。

---

## 复现方法

在安装当前 Reactor 构件后，从仓库根目录打包，然后在 `benchmarks/` 目录下运行：

```bash
mvn -q -f benchmarks/pom.xml clean package
cd benchmarks

java -jar target/benchmarks.jar CriterionMatchBenchmark -prof gc -f 1 -wi 3 -i 5 -rf json -rff results/CriterionMatchBenchmark.json
java -jar target/benchmarks.jar RouterRouteBenchmark -prof gc -f 1 -wi 3 -i 5 -rf json -rff results/RouterRouteBenchmark.json
java -jar target/benchmarks.jar KvTieredReadBenchmark -prof gc -f 1 -wi 3 -i 5 -rf json -rff results/KvTieredReadBenchmark.json
java -jar target/benchmarks.jar ProxyDelegateBenchmark -prof gc -f 1 -wi 3 -i 5 -rf json -rff results/ProxyDelegateBenchmark.json
```

上述相对路径对应的原始证据文件保存在 `benchmarks/results/<Class>.json`。
- GC 分析器输出中的 `gc.alloc.rate.norm` 表示该特定运行下每次操作观察到的内存分配。
- JMH 平均时间模式下的 Score 值为均值而非中位数。单个短时运行并不代表通用绝对延迟指标：JIT 行为、CPU 频率、容器 Quota、内存布局和共存负载均可能影响测试结果。所有数据应仅结合记录的 environment 与命令进行对比。

---

## 实测结果记录

于 2026-08-29 在完整合并候选分支上记录：工作区 `.worktrees/framework-convergence`（分支 `refactor/framework-convergence`），测试环境与命令与基准线保持一致（参见 `results/environment.txt`）。

测试环境：JDK 21.0.11 (Corretto), Linux 6.8.12-22-pve, AMD Ryzen 7 7735HS, 16 逻辑处理器，cgroup quota 见 `results/environment.txt`，单线程 Fork。

| 基准测试 | 平均耗时 (99.9% 置信区间) | `gc.alloc.rate` | `gc.alloc.rate.norm` | 原始证据文件 |
| :--- | :--- | :--- | :--- | :--- |
| Criterion 逻辑/属性谓词 | `54.663 ± 1.368 ns/op` | `1116.413 ± 27.804 MB/sec` | `64.000 ± 0.001 B/op` | `results/CriterionMatchBenchmark.{json,txt}` |
| Criterion 主体数值比较 | `4.033 ± 0.026 ns/op` | `0.007 ± 0.001 MB/sec` | 低于 profiler 测量精度 | `results/CriterionMatchBenchmark.{json,txt}` |
| Router 路由决策 | `35.526 ± 0.812 ns/op` | `3220.778 ± 73.606 MB/sec` | `120.000 ± 0.001 B/op` | `results/RouterRouteBenchmark.{json,txt}` |
| Tiered KV L1 读取 | `45.991 ± 1.682 ns/op` | `0.008 ± 0.014 MB/sec` | 低于 profiler 测量精度 | `results/KvTieredReadBenchmark.{json,txt}` |
| Proxy 委托方法调用 | `9.462 ± 0.226 ns/op` | `0.007 ± 0.001 MB/sec` | 低于 profiler 测量精度 | `results/ProxyDelegateBenchmark.{json,txt}` |

所有平均耗时波动均在 4% 以内，可测量的 `gc.alloc.rate.norm` 保持为 `64` / `120 B/op`，无任何性能劣化。多级 KV 的销毁期断言（预热及所有测量迭代期间 L2 `get` 调用次数为 0）在本次复测中全部通过。
