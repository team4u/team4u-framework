package com.team4u.framework.flow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.api.Retry;
import com.team4u.framework.flow.compiler.Compiler;
import com.team4u.framework.flow.compiler.ExecutableProjector;
import com.team4u.framework.flow.compiler.Logical;
import com.team4u.framework.flow.desc.FlowDescription;
import com.team4u.framework.flow.desc.FlowDescriptionBuilder;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.FlowBuildException;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.model.Recovery;
import com.team4u.framework.flow.model.Resumed;
import com.team4u.framework.flow.spi.ExecutableFlowVisitor;
import com.team4u.framework.flow.spi.OperationResolver;

/**
 * 不可变的强类型流程编排 DSL 核心入口类。
 *
 * <p>核心架构与语义规范：
 * <ul>
 *   <li><b>纯逻辑 AST 模型（Declaration Separation）</b>：Flow 仅构建和持有不可变的逻辑抽象语法树（AST），本身不分配线程、不维护执行状态、不可直接执行；需通过 {@link Local#compile} 编译为 {@link LocalExecutable} 或通过 {@link #project} 导出到 Durable 引擎中运行；</li>
 *   <li><b>不可变链式调用（Immutability）</b>：所有方法（如 {@code then}、{@code use}、{@code named}、{@code policy} 等）均严格遵循函数式不可变规范，每次调用均返回全新的 Flow 实例，原实例保持不变，天然线程安全；</li>
 *   <li><b>结构扁平化优化</b>：相邻的匿名流水线步骤（{@code then}）在底层自动扁平化合并为同一个 Sequence 列表，避免过深的递归与帧栈开销；而具名 {@link #scope} 则显式保留层次边界；</li>
 *   <li><b>四态流转规则</b>：在编排流水线中，仅 {@link Outcome.Accepted} 会驱动下一节点；{@link Outcome.Rejected}、{@link Outcome.Skipped}、{@link Outcome.Failed} 会短路并向上传播，直到命中相应的控制节点（如 Fallback/Retry）或作为最终结果输出。</li>
 * </ul>
 * </p>
 *
 * @param <I> 流程的初始输入数据类型
 * @param <O> 流程的最终输出数据类型
 * @author jay.wu
 */
public final class Flow<I, O> {
    private final Logical root;

    private Flow(Logical root) {
        this.root = Objects.requireNonNull(root, "root must not be null");
    }

    /**
     * 以单个操作实例构造最简的单步流程（Step）。
     *
     * @param operation 原子业务操作实例，不能为 null
     * @param <I>       输入类型
     * @param <O>       输出类型
     * @return 初始单步 {@link Flow} 实例
     * @throws NullPointerException 当 {@code operation} 为 null 时抛出
     */
    public static <I, O> Flow<I, O> step(Operation<I, O> operation) {
        return Flow.<I, I, O, O>invoke(binding(operation, Logical.BindingKind.OPERATION),
                value -> value, (ignored, value) -> value);
    }

    /**
     * 以延迟解析的操作 Class 构造最简单步流程。
     *
     * @param operationClass 操作契约/实现 Class，将在编译期由 {@link OperationResolver} 解析，不能为 null
     * @param <I>            输入类型
     * @param <O>            输出类型
     * @return 初始单步 {@link Flow} 实例
     * @throws NullPointerException 当 {@code operationClass} 为 null 时抛出
     */
    public static <I, O> Flow<I, O> step(
            Class<? extends Operation<I, O>> operationClass) {
        return step(operationClass, null);
    }

    /**
     * 以延迟解析的操作 Class 和 Spring/Bean 限定符构造单步流程。
     *
     * @param operationClass 操作契约 Class，不能为 null
     * @param qualifier      Spring/Bean 限定符名称（如 Bean 名称），可为 null
     * @param <I>            输入类型
     * @param <O>            输出类型
     * @return 初始单步 {@link Flow} 实例
     * @throws NullPointerException 当 {@code operationClass} 为 null 时抛出
     */
    public static <I, O> Flow<I, O> step(
            Class<? extends Operation<I, O>> operationClass, String qualifier) {
        return Flow.<I, I, O, O>invoke(
                binding(operationClass, qualifier, Logical.BindingKind.OPERATION),
                value -> value, (ignored, value) -> value);
    }

    /**
     * 顺序拼接单步操作：前序步骤的 Accepted 输出直接作为后续操作的输入。
     *
     * @param operation 后续操作实例，不能为 null
     * @param <N>       新流程的输出类型
     * @return 串联后的新 {@link Flow} 实例
     * @throws NullPointerException 当 {@code operation} 为 null 时抛出
     */
    public <N> Flow<I, N> then(Operation<O, N> operation) {
        return then(Flow.step(operation));
    }

    /**
     * 顺序拼接延迟解析的操作 Class。
     *
     * @param operationClass 后续操作 Class，不能为 null
     * @param <N>            新流程的输出类型
     * @return 串联后的新 {@link Flow} 实例
     * @throws NullPointerException 当 {@code operationClass} 为 null 时抛出
     */
    public <N> Flow<I, N> then(Class<? extends Operation<O, N>> operationClass) {
        return then(Flow.step(operationClass));
    }

    /**
     * 顺序拼接延迟解析的限定符操作 Class。
     *
     * @param operationClass 后续操作 Class，不能为 null
     * @param qualifier      限定符名称，可为 null
     * @param <N>            新流程的输出类型
     * @return 串联后的新 {@link Flow} 实例
     * @throws NullPointerException 当 {@code operationClass} 为 null 时抛出
     */
    public <N> Flow<I, N> then(
            Class<? extends Operation<O, N>> operationClass, String qualifier) {
        return then(Flow.step(operationClass, qualifier));
    }

    /**
     * 顺序拼接子流程：当前 Flow 的 Accepted 输出作为子流程 {@code next} 的输入。
     *
     * @param next 后续子流程，不能为 null
     * @param <N>  新流程的最终输出类型
     * @return 串联后的新 {@link Flow} 实例
     * @throws NullPointerException 当 {@code next} 为 null 时抛出
     */
    public <N> Flow<I, N> then(Flow<O, N> next) {
        Objects.requireNonNull(next, "next must not be null");
        return new Flow<I, N>(sequence(root, next.root));
    }

    /**
     * 投影调用并合并结果（Use 模式）：
     * 从当前流程的输出中通过 {@code project} 函数提取入参调用子操作，
     * 并将原输出与操作的 Accepted 返回值通过 {@code merge} 函数聚合为新的输出。
     *
     * @param operation 操作实例，不能为 null
     * @param project   入参提取投影函数，不能为 null
     * @param merge     结果合并函数，不能为 null
     * @param <P>       操作的输入参数类型
     * @param <R>       操作的输出返回值类型
     * @param <N>       合并后的最终新输出类型
     * @return 增强串联后的新 {@link Flow} 实例
     * @throws NullPointerException 当任何参数为 null 时抛出
     */
    public <P, R, N> Flow<I, N> use(Operation<P, R> operation,
                                     Function<? super O, ? extends P> project,
                                     BiFunction<? super O, ? super R, ? extends N> merge) {
        return then(invoke(binding(operation, Logical.BindingKind.OPERATION), project, merge));
    }

    /**
     * 投影调用并合并结果（延迟解析 Class）。
     *
     * @param operationClass 操作 Class，不能为 null
     * @param project        入参提取投影函数，不能为 null
     * @param merge          结果合并函数，不能为 null
     * @param <P>            操作的输入参数类型
     * @param <R>            操作的输出返回值类型
     * @param <N>            合并后的最终新输出类型
     * @return 增强串联后的新 {@link Flow} 实例
     * @throws NullPointerException 当任何必要参数为 null 时抛出
     */
    public <P, R, N> Flow<I, N> use(
            Class<? extends Operation<P, R>> operationClass,
            Function<? super O, ? extends P> project,
            BiFunction<? super O, ? super R, ? extends N> merge) {
        return use(operationClass, null, project, merge);
    }

    /**
     * 投影调用并合并结果（延迟解析 Class 与限定符）。
     *
     * @param operationClass 操作 Class，不能为 null
     * @param qualifier      限定符名称，可为 null
     * @param project        入参提取投影函数，不能为 null
     * @param merge          结果合并函数，不能为 null
     * @param <P>            操作的输入参数类型
     * @param <R>            操作的输出返回值类型
     * @param <N>            合并后的最终新输出类型
     * @return 增强串联后的新 {@link Flow} 实例
     * @throws NullPointerException 当任何必要参数为 null 时抛出
     */
    public <P, R, N> Flow<I, N> use(
            Class<? extends Operation<P, R>> operationClass, String qualifier,
            Function<? super O, ? extends P> project,
            BiFunction<? super O, ? super R, ? extends N> merge) {
        return then(invoke(binding(operationClass, qualifier, Logical.BindingKind.OPERATION),
                project, merge));
    }

    /**
     * 以具名作用域（Scope）包裹子流程，创建明确的拓扑与恢复边界。
     *
     * <p>具名 Scope 不会被相邻的匿名 Sequence 自动扁平化合并，常用于日志追踪分段或定义 Fallback 降级恢复的输入边界。</p>
     *
     * @param name 作用域名称，在同一层级内应保持唯一，不能为 null 或空白
     * @param body 被包裹的子流程，不能为 null
     * @param <I>  输入类型
     * @param <O>  输出类型
     * @return 具名作用域 {@link Flow} 实例
     * @throws NullPointerException     当入参为 null 时抛出
     * @throws IllegalArgumentException 当 {@code name} 为空白字符串时抛出
     */
    public static <I, O> Flow<I, O> scope(String name, Flow<I, O> body) {
        Objects.requireNonNull(body, "body must not be null");
        return new Flow<I, O>(new Logical.Sequence(Collections.singletonList(body.root), text(name, "scope name")));
    }

    /**
     * 为当前节点设置可读显示标签（Label），便于可视化图渲染与事件监控追踪。
     *
     * @param label 人类可读标签字符串，不能为 null 或空白
     * @return 携带标签的新 {@link Flow} 实例
     * @throws NullPointerException     当 {@code label} 为 null 时抛出
     * @throws IllegalArgumentException 当 {@code label} 为空白字符串时抛出
     */
    public Flow<I, O> named(String label) {
        return new Flow<I, O>(new Logical.Named(text(label, "label"), root));
    }

    /**
     * 构造动态条件路由起点（Route Start）：
     * 执行 {@code selector} 提取路由判别键，再通过 {@link RouteCases#caseOf} 与 {@link RouteCases#otherwise} 选择分支。
     *
     * @param selector 路由判别操作实例，返回路由键，不能为 null
     * @param <I>      流程输入类型
     * @param <K>      路由键类型
     * @return 路由构建起点 {@link RouteStart}
     * @throws NullPointerException 当 {@code selector} 为 null 时抛出
     */
    public static <I, K> RouteStart<I, K> route(Operation<I, K> selector) {
        return new RouteStart<I, K>(binding(selector, Logical.BindingKind.OPERATION));
    }

    /**
     * 构造动态条件路由起点（延迟解析 selector Class）。
     *
     * @param selectorClass 路由选择操作契约 Class，不能为 null
     * @param <I>           流程输入类型
     * @param <K>           路由键类型
     * @return 路由构建起点 {@link RouteStart}
     * @throws NullPointerException 当 {@code selectorClass} 为 null 时抛出
     */
    public static <I, K> RouteStart<I, K> route(
            Class<? extends Operation<I, K>> selectorClass) {
        return route(selectorClass, null);
    }

    /**
     * 构造动态条件路由起点（延迟解析 selector Class 与限定符）。
     *
     * @param selectorClass 路由选择操作契约 Class，不能为 null
     * @param qualifier     限定符名称，可为 null
     * @param <I>           流程输入类型
     * @param <K>           路由键类型
     * @return 路由构建起点 {@link RouteStart}
     * @throws NullPointerException 当 {@code selectorClass} 为 null 时抛出
     */
    public static <I, K> RouteStart<I, K> route(
            Class<? extends Operation<I, K>> selectorClass, String qualifier) {
        return new RouteStart<I, K>(binding(selectorClass, qualifier, Logical.BindingKind.OPERATION));
    }

    /**
     * 首选适用多路尝试（First-Applicable）：
     * 依次按顺序尝试执行各分支流程，首个产生非 {@link Outcome.Skipped}（如 Accepted/Rejected/Failed）的结果即作为最终输出；
     * 若全部分支均 Skipped 弃权，则最终结果为 Skipped。
     *
     * @param first     首选第一分支，不能为 null
     * @param remaining 候选后续分支数组，不能为 null
     * @param <I>       输入类型
     * @param <O>       输出类型
     * @return 多路尝试 {@link Flow} 实例
     * @throws NullPointerException 当入参或分支为 null 时抛出
     */
    @SafeVarargs
    public static <I, O> Flow<I, O> firstApplicable(
            Flow<I, O> first, Flow<I, O>... remaining) {
        Objects.requireNonNull(first, "first branch must not be null");
        Objects.requireNonNull(remaining, "remaining branches must not be null");
        List<Logical> branches = new ArrayList<Logical>();
        branches.add(first.root);
        for (Flow<I, O> branch : remaining) {
            branches.add(Objects.requireNonNull(branch, "branch must not be null").root);
        }
        return new Flow<I, O>(new Logical.Fallback(Logical.Fallback.Trigger.SKIPPED, branches));
    }

    /**
     * 失败降级恢复（Recover-With）：
     * 当当前流程执行发生 {@link Outcome.Failed} 失败时，将进入当前作用域时的原始输入与捕获的 {@link Failure}
     * 封装为 {@link Recovery} 传入 {@code fallback} 子流程进行补偿恢复。
     *
     * @param fallback 失败降级子流程，接收 {@link Recovery} 输入，不能为 null
     * @return 挂载失败恢复后的新 {@link Flow} 实例
     * @throws NullPointerException 当 {@code fallback} 为 null 时抛出
     */
    public Flow<I, O> recoverWith(Flow<Recovery<I>, O> fallback) {
        Objects.requireNonNull(fallback, "fallback must not be null");
        return new Flow<I, O>(new Logical.Fallback(Logical.Fallback.Trigger.FAILED,
                Arrays.asList(root, fallback.root)));
    }

    /**
     * 挂起等待（Await）：
     * 在当前流程之后追加异步等待挂起点；流程执行到此将进入挂起状态（Suspended），
     * 外部恢复时将注入指定类型的信号数据，并将原输出与恢复信号组合为 {@link Resumed} 作为后续输入。
     *
     * @param point 挂起点唯一类型化标识，不能为 null
     * @param <R>   恢复信号数据类型
     * @return 挂起后的新 {@link Flow} 实例
     * @throws NullPointerException 当 {@code point} 为 null 时抛出
     */
    public <R> Flow<I, Resumed<O, R>> await(ResumePoint<R> point) {
        return then(new Flow<O, Resumed<O, R>>(new Logical.Await(
                Objects.requireNonNull(point, "point must not be null"))));
    }

    /**
     * 附加内存无状态治理策略（Policy）：
     * 在目标步骤执行前执行 {@link Policy#before} 准入检查，在执行后执行 {@link Policy#after} 观察。
     *
     * @param policy        策略实例，不能为 null
     * @param keyProjection 策略键投影提取函数，不能为 null
     * @param <K>           策略键类型
     * @return 附加策略后的新 {@link Flow} 实例
     * @throws NullPointerException 当任何参数为 null 时抛出
     */
    public <K> Flow<I, O> policy(Policy<K> policy,
                                  Function<? super I, ? extends K> keyProjection) {
        return control(Logical.Control.Kind.POLICY,
                binding(policy, Logical.BindingKind.POLICY), keyProjection, null);
    }

    /**
     * 附加延迟解析的内存无状态治理策略 Class。
     *
     * @param policyClass   策略契约 Class，不能为 null
     * @param keyProjection 策略键投影提取函数，不能为 null
     * @param <K>           策略键类型
     * @return 附加策略后的新 {@link Flow} 实例
     * @throws NullPointerException 当任何参数为 null 时抛出
     */
    public <K> Flow<I, O> policy(Class<? extends Policy<K>> policyClass,
                                  Function<? super I, ? extends K> keyProjection) {
        return policy(policyClass, null, keyProjection);
    }

    /**
     * 附加延迟解析的限定符内存无状态治理策略 Class。
     *
     * @param policyClass   策略契约 Class，不能为 null
     * @param qualifier     限定符名称，可为 null
     * @param keyProjection 策略键投影提取函数，不能为 null
     * @param <K>           策略键类型
     * @return 附加策略后的新 {@link Flow} 实例
     * @throws NullPointerException 当任何必要参数为 null 时抛出
     */
    public <K> Flow<I, O> policy(Class<? extends Policy<K>> policyClass, String qualifier,
                                  Function<? super I, ? extends K> keyProjection) {
        return control(Logical.Control.Kind.POLICY,
                binding(policyClass, qualifier, Logical.BindingKind.POLICY), keyProjection, null);
    }

    /**
     * 附加持久化有状态治理策略（PersistentPolicy）：
     * 策略内部状态由引擎持久化（支持跨崩溃恢复与定时重试）。禁止在 Parallel 并行分支内使用。
     *
     * @param policy        持久化策略实例，不能为 null
     * @param keyProjection 策略键投影提取函数，不能为 null
     * @param <K>           策略键类型
     * @param <S>           策略持久化状态类型
     * @return 附加持久化策略后的新 {@link Flow} 实例
     * @throws NullPointerException 当任何参数为 null 时抛出
     */
    public <K, S> Flow<I, O> persistentPolicy(PersistentPolicy<K, S> policy,
                                               Function<? super I, ? extends K> keyProjection) {
        return control(Logical.Control.Kind.PERSISTENT_POLICY,
                binding(policy, Logical.BindingKind.PERSISTENT_POLICY), keyProjection, null);
    }

    /**
     * 附加延迟解析的持久化有状态治理策略 Class。
     *
     * @param policyClass   持久化策略契约 Class，不能为 null
     * @param keyProjection 策略键投影提取函数，不能为 null
     * @param <K>           策略键类型
     * @param <S>           策略持久化状态类型
     * @return 附加持久化策略后的新 {@link Flow} 实例
     * @throws NullPointerException 当任何参数为 null 时抛出
     */
    public <K, S> Flow<I, O> persistentPolicy(
            Class<? extends PersistentPolicy<K, S>> policyClass,
            Function<? super I, ? extends K> keyProjection) {
        return persistentPolicy(policyClass, null, keyProjection);
    }

    /**
     * 附加延迟解析的限定符持久化有状态治理策略 Class。
     *
     * @param policyClass   持久化策略契约 Class，不能为 null
     * @param qualifier     限定符名称，可为 null
     * @param keyProjection 策略键投影提取函数，不能为 null
     * @param <K>           策略键类型
     * @param <S>           策略持久化状态类型
     * @return 附加持久化策略后的新 {@link Flow} 实例
     * @throws NullPointerException 当任何必要参数为 null 时抛出
     */
    public <K, S> Flow<I, O> persistentPolicy(
            Class<? extends PersistentPolicy<K, S>> policyClass, String qualifier,
            Function<? super I, ? extends K> keyProjection) {
        return control(Logical.Control.Kind.PERSISTENT_POLICY,
                binding(policyClass, qualifier, Logical.BindingKind.PERSISTENT_POLICY),
                keyProjection, null);
    }

    /**
     * 失败重试治理（Retry）：
     * 当步骤产生 {@link Outcome.Failed} 时，在经过 {@code retry.backoff()} 退避延迟后重新尝试执行，
     * 最多尝试 {@code retry.maxAttempts()} 次（含首次执行）。
     *
     * @param retry 重试配置，不能为 null
     * @return 附加重试控制后的新 {@link Flow} 实例
     * @throws NullPointerException 当 {@code retry} 为 null 时抛出
     */
    public Flow<I, O> retry(Retry retry) {
        return control(Logical.Control.Kind.RETRY, null, Function.identity(),
                Objects.requireNonNull(retry, "retry must not be null"));
    }

    /**
     * 超时时限治理（Timeout）：
     * 为当前作用域设置执行时限，若超时未完成则产生错误码为 {@code TIMEOUT} 的 Failed 结果并终止作用域。
     *
     * @param duration 超时时长，必须为正数
     * @return 附加超时控制后的新 {@link Flow} 实例
     * @throws NullPointerException     当 {@code duration} 为 null 时抛出
     * @throws IllegalArgumentException 当 {@code duration} 为 0 或负数时抛出
     */
    public Flow<I, O> timeout(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        return control(Logical.Control.Kind.TIMEOUT, null, Function.identity(), duration);
    }

    /**
     * 恒等流程（Identity Flow）：直接透传输入数据作为 Accepted 输出。
     *
     * @param <T> 输入输出类型
     * @return 恒等 {@link Flow} 实例
     */
    public static <T> Flow<T, T> identity() {
        return new Flow<T, T>(new Logical.Complete(null, true));
    }

    /**
     * 常量成功终态流程：无论输入为何，直接返回固定的 Accepted 结果。
     *
     * @param value 输出值，不能为 null
     * @param <I>   输入类型
     * @param <O>   输出类型
     * @return 常量成功 {@link Flow}
     * @throws NullPointerException 当 {@code value} 为 null 时抛出
     */
    public static <I, O> Flow<I, O> accepted(O value) {
        return complete(Outcome.accepted(value));
    }

    /**
     * 常量拒绝终态流程：无论输入为何，直接返回固定的 Rejected 结果。
     *
     * @param reason 拒绝原因，不能为 null
     * @param <I>    输入类型
     * @param <O>    输出类型
     * @return 常量拒绝 {@link Flow}
     * @throws NullPointerException 当 {@code reason} 为 null 时抛出
     */
    public static <I, O> Flow<I, O> rejected(Reason reason) {
        return complete(Outcome.rejected(reason));
    }

    /**
     * 常量跳过终态流程：无论输入为何，直接返回固定的 Skipped 结果。
     *
     * @param reason 跳过原因，不能为 null
     * @param <I>    输入类型
     * @param <O>    输出类型
     * @return 常量跳过 {@link Flow}
     * @throws NullPointerException 当 {@code reason} 为 null 时抛出
     */
    public static <I, O> Flow<I, O> skipped(Reason reason) {
        return complete(Outcome.skipped(reason));
    }

    /**
     * 常量失败终态流程：无论输入为何，直接返回固定的 Failed 结果。
     *
     * @param failure 失败信息，不能为 null
     * @param <I>     输入类型
     * @param <O>     输出类型
     * @return 常量失败 {@link Flow}
     * @throws NullPointerException 当 {@code failure} 为 null 时抛出
     */
    public static <I, O> Flow<I, O> failed(Failure failure) {
        return complete(Outcome.failed(failure));
    }

    /**
     * 结构化并行分支起点（Parallel）：
     * 声明若干并行执行的 {@link Branch}，后续通过 {@link ParallelBuilder#join} 指定汇合归约策略。
     *
     * @param branches 并行分支令牌数组，至少包含一个分支
     * @param <I>      并行流程的输入类型
     * @return 并行构建器 {@link ParallelBuilder}
     * @throws IllegalArgumentException 当分支数组为空或存在重复分支名称时抛出
     */
    @SafeVarargs
    public static <I> ParallelBuilder<I> parallel(Branch<I, ?>... branches) {
        return new ParallelBuilder<I>(Arrays.asList(branches));
    }

    /**
     * 获取内部根逻辑节点。
     *
     * @return 逻辑根节点
     */
    public Logical root() {
        return root;
    }

    /** 把 Logical.Control 包裹在当前 root 之上；keyProjection 擦除类型后存入配置。 */
    private <K> Flow<I, O> control(Logical.Control.Kind kind, Logical.Binding binding,
                                    Function<? super I, ? extends K> keyProjection,
                                    Object configuration) {
        Objects.requireNonNull(keyProjection, "keyProjection must not be null");
        @SuppressWarnings("unchecked")
        Function<Object, Object> erased = value -> keyProjection.apply((I) value);
        return new Flow<I, O>(new Logical.Control(kind, root, binding, erased, configuration));
    }

    /** 把 invoke 节点的 project/merge 擦除为 Object 类型，避免在 Logical 层暴露类型参数。 */
    private static <I, P, R, O> Flow<I, O> invoke(
            Logical.Binding binding, Function<? super I, ? extends P> project,
            BiFunction<? super I, ? super R, ? extends O> merge) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(merge, "merge must not be null");
        @SuppressWarnings("unchecked")
        Function<Object, Object> erasedProject = value -> project.apply((I) value);
        @SuppressWarnings("unchecked")
        BiFunction<Object, Object, Object> erasedMerge =
                (state, value) -> merge.apply((I) state, (R) value);
        return new Flow<I, O>(new Logical.Invoke(binding, erasedProject, erasedMerge));
    }

    private static <I, O> Flow<I, O> complete(Outcome<O> outcome) {
        return new Flow<I, O>(new Logical.Complete(
                Objects.requireNonNull(outcome, "outcome must not be null"), false));
    }

    /**
     * 扁平化相邻的匿名 Sequence：相邻 then 调用不会嵌套，而是合并为同一个子节点列表，
     * 带具名 scope 的 Sequence 不被合并。
     */
    private static Logical sequence(Logical left, Logical right) {
        List<Logical> children = new ArrayList<Logical>();
        if (left instanceof Logical.Sequence) {
            Logical.Sequence sequence = (Logical.Sequence) left;
            if (sequence.scopeName() == null) {
                children.addAll(sequence.children());
            } else {
                children.add(left);
            }
        } else {
            children.add(left);
        }
        if (right instanceof Logical.Sequence) {
            Logical.Sequence sequence = (Logical.Sequence) right;
            if (sequence.scopeName() == null) {
                children.addAll(sequence.children());
            } else {
                children.add(right);
            }
        } else {
            children.add(right);
        }
        return new Logical.Sequence(children, null);
    }

    private static Logical.Binding binding(Object instance, Logical.BindingKind kind) {
        Objects.requireNonNull(instance, "binding instance must not be null");
        return new Logical.Binding(instance, declaredContract(instance, kind), null, kind);
    }

    private static Logical.Binding binding(Class<?> contract, String qualifier,
                                            Logical.BindingKind kind) {
        Objects.requireNonNull(contract, "contract must not be null");
        String normalized = qualifier == null ? null : text(qualifier, "qualifier");
        return new Logical.Binding(null, contract, normalized, kind);
    }

    /**
     * 由实例推断其声明契约：对 lambda/代理取其第一个 Operation/Policy 子接口，
     * 对普通类直接取实现类。
     */
    private static Class<?> declaredContract(Object instance, Logical.BindingKind kind) {
        Class<?> marker;
        if (kind == Logical.BindingKind.OPERATION) {
            marker = Operation.class;
        } else if (kind == Logical.BindingKind.POLICY) {
            marker = Policy.class;
        } else if (kind == Logical.BindingKind.PERSISTENT_POLICY) {
            marker = PersistentPolicy.class;
        } else {
            throw new IllegalStateException("Unknown BindingKind: " + kind);
        }
        Class<?> type = instance.getClass();
        if (type.isSynthetic() || java.lang.reflect.Proxy.isProxyClass(type)) {
            for (Class<?> candidate : type.getInterfaces()) {
                if (marker.isAssignableFrom(candidate) && candidate != marker) {
                    return candidate;
                }
            }
            return marker;
        }
        return type;
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    /**
     * 路由构建起点辅助类，持有路由判别器，等待声明匹配分支（caseOf / otherwise）。
     *
     * @param <I> 输入数据类型
     * @param <K> 路由判别键类型
     */
    public static final class RouteStart<I, K> {
        private final Logical.Binding selector;

        private RouteStart(Logical.Binding selector) {
            this.selector = selector;
        }

        /**
         * 声明首个匹配条件分支。
         *
         * @param key    分支匹配键，不能为 null
         * @param branch 命中后执行的子流程，不能为 null
         * @param <O>    分支输出类型
         * @return 路由分支集合构建器 {@link RouteCases}
         */
        public <O> RouteCases<I, K, O> caseOf(K key, Flow<I, O> branch) {
            return new RouteCases<I, K, O>(selector, Collections.emptyList()).caseOf(key, branch);
        }

        /**
         * 直接声明默认兜底分支（无匹配分支时）。
         *
         * @param branch 兜底子流程，不能为 null
         * @param <O>    输出类型
         * @return 完整的路由 {@link Flow}
         */
        public <O> Flow<I, O> otherwise(Flow<I, O> branch) {
            return new Flow<I, O>(new Logical.Route(selector, Collections.emptyList(),
                    Objects.requireNonNull(branch, "branch must not be null").root));
        }
    }

    /**
     * 路由分支集合构建器，支持链式追加 {@link #caseOf} 并以 {@link #otherwise} 或 {@link #withoutOtherwise} 终结。
     *
     * @param <I> 输入数据类型
     * @param <K> 路由判别键类型
     * @param <O> 分支输出结果类型
     */
    public static final class RouteCases<I, K, O> {
        private final Logical.Binding selector;
        private final List<Logical.Route.Case> cases;

        private RouteCases(Logical.Binding selector, List<Logical.Route.Case> cases) {
            this.selector = selector;
            this.cases = cases;
        }

        /**
         * 追加一条匹配分支。
         *
         * @param key    分支匹配键，不能与已有分支重复，不能为 null
         * @param branch 命中后执行的子流程，不能为 null
         * @return 更新后的分支构建器 {@link RouteCases}
         * @throws FlowBuildException 当存在重复匹配键时抛出
         */
        public RouteCases<I, K, O> caseOf(K key, Flow<I, O> branch) {
            Objects.requireNonNull(key, "case key must not be null");
            Objects.requireNonNull(branch, "branch must not be null");
            for (Logical.Route.Case candidate : cases) {
                if (candidate.key().equals(key)) {
                    throw new FlowBuildException(Collections.singletonList(new FlowBuildException.Problem(
                            "DUPLICATE_ROUTE_CASE", "$", "Duplicate route case: " + key)));
                }
            }
            List<Logical.Route.Case> copy = new ArrayList<Logical.Route.Case>(cases);
            copy.add(new Logical.Route.Case(key, branch.root));
            return new RouteCases<I, K, O>(selector, Collections.unmodifiableList(copy));
        }

        /**
         * 声明默认兜底分支（当所有 caseOf 均未命中时执行）。
         *
         * @param branch 兜底分支流程，不能为 null
         * @return 完整的路由 {@link Flow} 实例
         */
        public Flow<I, O> otherwise(Flow<I, O> branch) {
            return new Flow<I, O>(new Logical.Route(selector, cases,
                    Objects.requireNonNull(branch, "branch must not be null").root));
        }

        /**
         * 声明无默认兜底分支（当所有 caseOf 均未命中时直接产生 {@link Outcome.Skipped}）。
         *
         * @return 完整的路由 {@link Flow} 实例
         */
        public Flow<I, O> withoutOtherwise() {
            return new Flow<I, O>(new Logical.Route(selector, cases, null));
        }
    }

    /**
     * 结构化并行构建器：校验分支唯一性并结合 {@link JoinStrategy} 合成并行流程节点。
     *
     * @param <I> 输入数据类型
     */
    public static final class ParallelBuilder<I> {
        private final List<Branch<I, ?>> branches;

        private ParallelBuilder(List<Branch<I, ?>> branches) {
            if (branches.isEmpty()) throw new IllegalArgumentException(
                    "parallel requires at least one branch");
            LinkedHashMap<String, Branch<I, ?>> names = new LinkedHashMap<String, Branch<I, ?>>();
            for (Branch<I, ?> branch : branches) {
                Objects.requireNonNull(branch, "branch must not be null");
                if (names.put(branch.name(), branch) != null) {
                    throw new FlowBuildException(Collections.singletonList(new FlowBuildException.Problem(
                            "DUPLICATE_BRANCH", "$", "Duplicate branch: " + branch.name())));
                }
            }
            this.branches = Collections.unmodifiableList(new ArrayList<Branch<I, ?>>(branches));
        }

        /**
         * 指定并行汇聚归约策略（JoinStrategy）并完成并行节点构造。
         *
         * @param join 汇聚策略，不能为 null
         * @param <O>  汇聚输出类型
         * @return 完整的并行 {@link Flow} 实例
         * @throws NullPointerException 当 {@code join} 为 null 时抛出
         */
        public <O> Flow<I, O> join(JoinStrategy<O> join) {
            Objects.requireNonNull(join, "join must not be null");
            List<Logical.ParallelBranch> logical = new ArrayList<Logical.ParallelBranch>();
            for (Branch<I, ?> branch : branches) {
                logical.add(new Logical.ParallelBranch(branch, branch.flow().root));
            }
            return new Flow<I, O>(new Logical.Parallel(logical, join));
        }
    }

    /**
     * 导出当前逻辑流程的结构化只读描述模型（未命名流程）。
     *
     * @return 流程结构描述模型 {@link FlowDescription}
     */
    public FlowDescription describe() {
        return describe(null);
    }

    /**
     * 导出带有指定流程标识的结构化只读描述模型（常用于生成可视化 Mermaid 图或文本拓扑结构）。
     *
     * @param flowId 流程标识，可为 null
     * @return 流程结构描述模型 {@link FlowDescription}
     */
    public FlowDescription describe(String flowId) {
        return new FlowDescription(flowId, FlowDescriptionBuilder.describe(root, "$"));
    }

    /**
     * 使用默认拒绝解析器将当前流程校验并解析后，通过 {@link ExecutableFlowVisitor} 投影为目标运行时拓扑结构。
     *
     * @param visitor 可执行流访问者 SPI，不能为 null
     * @param <R>     投影结果类型
     * @return 访问者投影生成的实例
     */
    public <R> R project(ExecutableFlowVisitor<R> visitor) {
        return project(OperationResolver.rejecting(), visitor);
    }

    /**
     * 使用指定的 {@link OperationResolver} 将当前流程校验并解析后，通过 {@link ExecutableFlowVisitor} 投影为目标运行时拓扑结构。
     *
     * @param resolver 组件解析器，不能为 null
     * @param visitor  可执行流访问者 SPI，不能为 null
     * @param <R>      投影结果类型
     * @return 访问者投影生成的实例
     * @throws NullPointerException 当参数为 null 时抛出
     * @throws FlowBuildException   当流程结构校验失败时抛出
     */
    public <R> R project(OperationResolver resolver, ExecutableFlowVisitor<R> visitor) {
        Objects.requireNonNull(visitor, "visitor must not be null");
        Objects.requireNonNull(resolver, "resolver must not be null");
        Compiler.Compiled compiled = Compiler.compile(this, resolver);
        return ExecutableProjector.project(compiled.root(), visitor);
    }
}

