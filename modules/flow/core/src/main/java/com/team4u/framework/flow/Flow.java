package com.team4u.framework.flow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import com.team4u.framework.flow.api.Branch;
import com.team4u.framework.flow.api.ContextualJoinStrategy;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.compiler.Compiler;
import com.team4u.framework.flow.compiler.ExecutableProjector;
import com.team4u.framework.flow.compiler.FlowPaths;
import com.team4u.framework.flow.compiler.Logical;
import com.team4u.framework.flow.desc.FlowDescription;
import com.team4u.framework.flow.desc.FlowDescriptionBuilder;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.FlowBuildException;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.model.Recovery;
import com.team4u.framework.flow.model.Resumed;
import com.team4u.framework.flow.spi.BindingResolver;
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
 *   <li><b>四态流转规则</b>：在编排流水线中，仅 {@link Outcome.Accepted} 会驱动下一节点；{@link Outcome.Rejected}、{@link Outcome.Skipped}、{@link Outcome.Failed} 会短路并向上传播，直到命中相应的控制节点（如 Fallback/Policy/PersistentPolicy）或作为最终结果输出。</li>
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
     * 顺序拼接“可选步骤”：该操作总会执行，但其 {@link Outcome.Skipped} 不终止流水线，
     * 而是将进入该操作前的原值透传给后续节点继续执行。
     *
     * <p>结果语义：</p>
     * <ul>
     *   <li>{@link Outcome.Accepted}：新输出值正常作为后续节点的输入；</li>
     *   <li>{@link Outcome.Skipped}：以本操作的输入值（原值）继续执行后续节点，流水线不中断；</li>
     *   <li>{@link Outcome.Rejected} / {@link Outcome.Failed}：照常短路并向上传播，后续节点不执行。</li>
     * </ul>
     *
     * <p>实现上脱糖为 {@code firstApplicable(step, identity())}（SKIPPED 触发的 Fallback），
     * 不引入新的运行时节点，Local 与 Durable 行为完全一致；该步骤的 Skipped 结果仍会通过
     * 节点完成事件正常上报，不会静默吞掉。</p>
     *
     * <p>类型约束：仅支持同类型 {@code O -> O} 的操作。Skipped 不携带输出载荷，无法为
     * 类型转换节点（{@code O -> N}）安全提供续传值；跨类型场景应显式提供默认输出。</p>
     *
     * @param operation 同类型业务操作实例，不能为 null
     * @return 串联后的新 {@link Flow} 实例
     * @throws NullPointerException 当 {@code operation} 为 null 时抛出
     */
    public Flow<I, O> thenOptional(Operation<O, O> operation) {
        return thenOptional(Flow.step(operation));
    }

    /**
     * 顺序拼接延迟解析的“可选步骤”操作 Class，语义同 {@link #thenOptional(Operation)}。
     *
     * @param operationClass 同类型操作契约 Class，将在编译期由 {@link OperationResolver} 解析，不能为 null
     * @return 串联后的新 {@link Flow} 实例
     * @throws NullPointerException 当 {@code operationClass} 为 null 时抛出
     */
    public Flow<I, O> thenOptional(Class<? extends Operation<O, O>> operationClass) {
        return thenOptional(Flow.step(operationClass));
    }

    /**
     * 顺序拼接延迟解析的限定符“可选步骤”操作 Class，语义同 {@link #thenOptional(Operation)}。
     *
     * @param operationClass 同类型操作契约 Class，不能为 null
     * @param qualifier      Spring/Bean 限定符名称（如 Bean 名称），可为 null
     * @return 串联后的新 {@link Flow} 实例
     * @throws NullPointerException 当 {@code operationClass} 为 null 时抛出
     */
    public Flow<I, O> thenOptional(
            Class<? extends Operation<O, O>> operationClass, String qualifier) {
        return thenOptional(Flow.step(operationClass, qualifier));
    }

    /**
     * 顺序拼接“可选子流程”：子流程总会执行，但其<b>最终</b> {@link Outcome.Skipped} 不终止流水线，
     * 而是回退为进入该子流程前的值继续执行后续节点。
     *
     * <p>结果语义：</p>
     * <ul>
     *   <li>子流程最终 {@link Outcome.Accepted}：其输出正常作为后续节点的输入；</li>
     *   <li>子流程最终 {@link Outcome.Skipped}：以<b>进入子流程前</b>的值继续执行后续节点；</li>
     *   <li>子流程最终 {@link Outcome.Rejected} / {@link Outcome.Failed}：照常短路并向上传播。</li>
     * </ul>
     *
     * <p>注意：整个子流程构成一个可选作用域（optional scope）——回退值是进入子流程前的值，
     * <b>而不是</b>子流程内部最后一次 Accepted 的中间值。若子流程内部第一步 Accepted、
     * 第二步 Skipped，最终仍回退到子流程入口处的原始输入。</p>
     *
     * <p>实现上脱糖为 {@code firstApplicable(next, identity())}（SKIPPED 触发的 Fallback），
     * 不引入新的运行时节点，Local 与 Durable 行为完全一致。</p>
     *
     * <p>类型约束：仅支持同类型 {@code O -> O} 的子流程，原因同 {@link #thenOptional(Operation)}。</p>
     *
     * @param next 同类型后续子流程，不能为 null
     * @return 串联后的新 {@link Flow} 实例
     * @throws NullPointerException 当 {@code next} 为 null 时抛出
     */
    public Flow<I, O> thenOptional(Flow<O, O> next) {
        Objects.requireNonNull(next, "next must not be null");
        return then(Flow.firstApplicable(next, Flow.<O>identity()));
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
     * 开启链式投影调用构建器（UseBuilder 模式）：
     * 支持分阶段声明入参投影、步骤修饰器（如 named、timeout 等）以及结果合并策略，
     * 避免多类型泛型参数在单个方法中互相干扰。
     *
     * @param operation 目标操作实例，不能为 null
     * @param <P>       操作的输入参数类型
     * @param <R>       操作的输出返回值类型
     * @return 投影阶段构建器 {@link UseProjectStage}
     */
    public <P, R> UseProjectStage<I, O, P, R> use(Operation<P, R> operation) {
        return new UseProjectStageImpl<I, O, P, R>(this, binding(operation, Logical.BindingKind.OPERATION));
    }

    /**
     * 开启链式投影调用构建器（延迟解析操作 Class）。
     *
     * @param operationClass 目标操作契约 Class，不能为 null
     * @param <P>            操作的输入参数类型
     * @param <R>            操作的输出返回值类型
     * @return 投影阶段构建器 {@link UseProjectStage}
     */
    public <P, R> UseProjectStage<I, O, P, R> use(Class<? extends Operation<P, R>> operationClass) {
        return use(operationClass, null);
    }

    /**
     * 开启链式投影调用构建器（延迟解析操作 Class 与限定符）。
     *
     * @param operationClass 目标操作契约 Class，不能为 null
     * @param qualifier      Spring/Bean 限定符名称，可为 null
     * @param <P>            操作的输入参数类型
     * @param <R>            操作的输出返回值类型
     * @return 投影阶段构建器 {@link UseProjectStage}
     */
    public <P, R> UseProjectStage<I, O, P, R> use(
            Class<? extends Operation<P, R>> operationClass, String qualifier) {
        return new UseProjectStageImpl<I, O, P, R>(this, binding(operationClass, qualifier, Logical.BindingKind.OPERATION));
    }

    /**
     * 基于条件谓词判定执行分支流程（when/otherwise 便捷 API）：
     * 当满足谓词条件时执行 {@code branch}，不满足时转入 {@link WhenBuilder#otherwise(Flow)} 兜底流程。
     *
     * <p><strong>约束：</strong>
     * 条件谓词应当是确定性的（deterministic）且无副作用（side-effect-free），以确保在持久化重算时行为一致。</p>
     *
     * @param predicate 条件谓词，不能为 null
     * @param branch    条件满足时执行的目标子流程，不能为 null
     * @param <N>       分支输出数据类型
     * @return 条件分支构建器 {@link WhenBuilder}
     */
    public <N> WhenBuilder<I, O, N> when(Predicate<? super O> predicate, Flow<O, N> branch) {
        Objects.requireNonNull(predicate, "predicate must not be null");
        Objects.requireNonNull(branch, "branch must not be null");
        return new WhenBuilder<I, O, N>(this, Collections.singletonList(new WhenBranch<O, N>(predicate, branch)));
    }

    /**
     * 旁路执行业务操作（tap 便捷 API）：
     * 使用当前流程输出作为入参调用目标操作；当操作执行结果为 Accepted 时，保留并透传当前原输入继续后续流程；
     * 当操作产生 Rejected/Skipped/Failed 等非 Accepted 结果时，原样向上传播短路。
     *
     * @param operation 旁路操作实例，不能为 null
     * @return 增强串联后的新 {@link Flow} 实例
     */
    public Flow<I, O> tap(Operation<O, ?> operation) {
        return use(operation).project(Function.identity()).discardResult();
    }

    /**
     * 旁路执行业务操作（延迟解析 Class）。
     *
     * @param operationClass 旁路操作契约 Class，不能为 null
     * @return 增强串联后的新 {@link Flow} 实例
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Flow<I, O> tap(Class<? extends Operation<O, ?>> operationClass) {
        return tap(operationClass, null);
    }

    /**
     * 旁路执行业务操作（延迟解析 Class 与限定符）。
     *
     * @param operationClass 旁路操作契约 Class，不能为 null
     * @param qualifier      限定符名称，可为 null
     * @return 增强串联后的新 {@link Flow} 实例
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Flow<I, O> tap(Class<? extends Operation<O, ?>> operationClass, String qualifier) {
        return ((UseProjectStage) use((Class) operationClass, qualifier)).project(Function.identity()).discardResult();
    }

    /**
     * 旁路消费当前流程输出（主要用于内存监控、指标统计或轻量观察）。
     *
     * <p><strong>Durable 注意事项：</strong>
     * {@code tap(Consumer)} 不应用于持久化模式（Durable）下的外部业务副作用（如发送 MQ、扣款或审计）。
     * 外部业务副作用应使用 {@link #tap(Operation)}，以便利用 {@link OperationContext#invocationId()} 实现幂等重放安全。</p>
     *
     * @param consumer 输出数据消费函数，不能为 null
     * @return 增强串联后的新 {@link Flow} 实例
     */
    public Flow<I, O> tap(java.util.function.Consumer<? super O> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        Operation<O, Boolean> op = (ctx, in) -> {
            consumer.accept(in);
            return Outcome.accepted(Boolean.TRUE);
        };
        return tap(op);
    }

    /**
     * 结构化适配并转换子流程输入输出（Adapter 核心原语）：
     * 将任意独立子流程嵌入当前流程上下文，从父输入提取投影入参，并在子流程产生 Accepted 结果后合并回父状态。
     *
     * @param body    被适配的目标子流程，不能为 null
     * @param project 输入投影函数，不能为 null
     * @param merge   结果合并函数，不能为 null
     * @param <S>     外部状态类型
     * @param <P>     子流程输入类型
     * @param <R>     子流程输出类型
     * @param <N>     合并后输出类型
     * @return 适配后的 Flow 实例
     */
    public static <S, P, R, N> Flow<S, N> adapt(
            Flow<P, R> body,
            Function<? super S, ? extends P> project,
            BiFunction<? super S, ? super R, ? extends N> merge) {
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(merge, "merge must not be null");
        @SuppressWarnings("unchecked")
        Function<Object, Object> erasedProject = value -> project.apply((S) value);
        @SuppressWarnings("unchecked")
        BiFunction<Object, Object, Object> erasedMerge = (state, res) -> merge.apply((S) state, (R) res);
        return new Flow<S, N>(new Logical.Adapter(body.root, erasedProject, erasedMerge));
    }

    /**
     * 顺序串联结构化适配子流程（Adapter 实例模式）。
     *
     * @param body    被适配的目标子流程，不能为 null
     * @param project 输入投影函数，不能为 null
     * @param merge   结果合并函数，不能为 null
     * @param <P>     子流程输入类型
     * @param <R>     子流程输出类型
     * @param <N>     合并后输出类型
     * @return 串联后的 Flow 实例
     */
    public <P, R, N> Flow<I, N> thenAdapt(
            Flow<P, R> body,
            Function<? super O, ? extends P> project,
            BiFunction<? super O, ? super R, ? extends N> merge) {
        return then(adapt(body, project, merge));
    }

    /**
     * 创建基于结构化并行与保序上下文汇聚的状态填充流水线构建器（parallelFill 便捷 API）。
     *
     * @return parallelFill 构建器
     */
    public ParallelFillBuilder<I, O> parallelFill() {
        return new ParallelFillBuilder<I, O>(this);
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
     * 超时时限治理（Timeout）：
     * 为当前作用域设置执行时限，若超时未完成则产生错误码为 {@code TIMEOUT} 的 Failed 结果并终止作用域。
     *
     * @param duration 超时时长，必须为正数
     * @return 附加超时控制后的新 {@link Flow} 实例
     * @throws NullPointerException     当 {@code duration} 为 null 时抛出
     * @throws IllegalArgumentException 当 {@code duration} 为 0、负数或超出时间线可表示范围
     *                                  （如近乎 {@link Long#MAX_VALUE} 纳秒的极值导致
     *                                  {@code Instant.now().plus(duration)} 溢出）时抛出
     */
    public Flow<I, O> timeout(Duration duration) {
        duration = requireValidTimeout(duration);
        return control(Logical.Control.Kind.TIMEOUT, null, Function.identity(), duration);
    }

    static Duration requireValidTimeout(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        try {
            Instant.now().plus(duration);
        } catch (RuntimeException overflow) {
            throw new IllegalArgumentException(
                    "duration is too large to represent a deadline: " + duration, overflow);
        }
        return duration;
    }

    /**
     * 恒等流程（Identity Flow）：直接透传输入数据作为 Accepted 输出。
     *
     * @param <T> 输入输出类型
     * @return 恒等 {@link Flow} 实例
     */
    public static <T> Flow<T, T> identity() {
        return new Flow<T, T>(Logical.Complete.identityNode());
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
     * <p><strong>输入只读约定：</strong>
     * 各并行分支必须将父级输入视为只读。若需要并发计算并就地丰富/合并上下文状态，应使用 {@link #parallelFill()}。</p>
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
        return new Flow<I, O>(Logical.Complete.constant(
                Objects.requireNonNull(outcome, "outcome must not be null")));
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
        } else if (kind == Logical.BindingKind.JOIN) {
            marker = JoinStrategy.class;
        } else if (kind == Logical.BindingKind.CONTEXTUAL_JOIN) {
            marker = ContextualJoinStrategy.class;
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
     * <p><b>键相等语义</b>：分支匹配与重复键检测均基于 {@code key.equals(Object)} 判等。
     * 因此路由键应选用正确实现 equals/hashCode 的类型（如 String、Integer、枚举、record 等）；
     * 数组类型（如 {@code byte[]}、{@code Object[]}）的 equals 为引用相等，既无法可靠去重，
     * 也无法在运行时命中，传入数组键将在构建期直接拒绝并报告 {@code ARRAY_ROUTE_KEY} 问题。</p>
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
         * <p>键相等语义与数组键限制参见类级文档；重复键检测基于 equals。</p>
         *
         * @param key    分支匹配键，不能与已有分支重复，不能为 null
         * @param branch 命中后执行的子流程，不能为 null
         * @return 更新后的分支构建器 {@link RouteCases}
         * @throws FlowBuildException 当存在重复匹配键或传入数组键时抛出
         */
        public RouteCases<I, K, O> caseOf(K key, Flow<I, O> branch) {
            Objects.requireNonNull(key, "case key must not be null");
            Objects.requireNonNull(branch, "branch must not be null");
            if (key.getClass().isArray()) {
                throw new FlowBuildException(Collections.singletonList(new FlowBuildException.Problem(
                        "ARRAY_ROUTE_KEY", "$",
                        "Route case key must not be an array (equals is reference-based): "
                                + key.getClass().getComponentType().getName())));
            }
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
     * 结构化并行构建器：校验分支唯一性（同一并行块内分支名唯一，不同并行块允许复用同名）
     * 并结合 {@link JoinStrategy} 合成并行流程节点。
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
            Logical.BindingKind kind = join instanceof ContextualJoinStrategy
                    ? Logical.BindingKind.CONTEXTUAL_JOIN
                    : Logical.BindingKind.JOIN;
            return new Flow<I, O>(new Logical.Parallel(logical, binding(join, kind)));
        }

        /**
         * 指定基于组件契约类型的并行汇聚归约策略，延迟至运行期通过解析器解析单例 Bean 实例。
         *
         * @param joinClass 汇聚策略契约接口或实现类，不能为 null
         * @param <O>       汇聚输出类型
         * @return 完整的并行 {@link Flow} 实例
         * @throws NullPointerException 当 {@code joinClass} 为 null 时抛出
         */
        public <O> Flow<I, O> join(Class<? extends JoinStrategy<O>> joinClass) {
            return join(joinClass, null);
        }

        /**
         * 指定基于组件契约类型与限定符的并行汇聚归约策略，延迟至运行期通过解析器解析单例 Bean 实例。
         *
         * @param joinClass 汇聚策略契约接口或实现类，不能为 null
         * @param qualifier 目标 Bean 限定符（如 Spring Bean 名称），可为 null
         * @param <O>       汇聚输出类型
         * @return 完整的并行 {@link Flow} 实例
         * @throws NullPointerException 当 {@code joinClass} 为 null 时抛出
         */
        public <O> Flow<I, O> join(Class<? extends JoinStrategy<O>> joinClass, String qualifier) {
            Objects.requireNonNull(joinClass, "joinClass must not be null");
            List<Logical.ParallelBranch> logical = new ArrayList<Logical.ParallelBranch>();
            for (Branch<I, ?> branch : branches) {
                logical.add(new Logical.ParallelBranch(branch, branch.flow().root));
            }
            return new Flow<I, O>(new Logical.Parallel(logical, binding(joinClass, qualifier, Logical.BindingKind.JOIN)));
        }

        /**
         * 指定带父节点输入上下文的并行汇聚归约策略（ContextualJoinStrategy）并完成并行节点构造。
         *
         * @param join 上下文汇聚策略，不能为 null
         * @param <O>  汇聚输出类型
         * @return 完整的并行 {@link Flow} 实例
         * @throws NullPointerException 当 {@code join} 为 null 时抛出
         */
        public <O> Flow<I, O> join(ContextualJoinStrategy<? super I, O> join) {
            Objects.requireNonNull(join, "join must not be null");
            List<Logical.ParallelBranch> logical = new ArrayList<Logical.ParallelBranch>();
            for (Branch<I, ?> branch : branches) {
                logical.add(new Logical.ParallelBranch(branch, branch.flow().root));
            }
            return new Flow<I, O>(new Logical.Parallel(logical, binding(join, Logical.BindingKind.CONTEXTUAL_JOIN)));
        }

        /**
         * 指定基于组件契约类型的带父节点输入上下文的并行汇聚归约策略，延迟至运行期通过解析器解析单例 Bean 实例。
         *
         * @param joinClass 上下文汇聚策略契约接口或实现类，不能为 null
         * @param <O>       汇聚输出类型
         * @return 完整的并行 {@link Flow} 实例
         * @throws NullPointerException 当 {@code joinClass} 为 null 时抛出
         */
        public <O> Flow<I, O> joinContextual(Class<? extends ContextualJoinStrategy<? super I, O>> joinClass) {
            return joinContextual(joinClass, null);
        }

        /**
         * 指定基于组件契约类型与限定符的带父节点输入上下文的并行汇聚归约策略，延迟至运行期通过解析器解析单例 Bean 实例。
         *
         * @param joinClass 上下文汇聚策略契约接口或实现类，不能为 null
         * @param qualifier 目标 Bean 限定符（如 Spring Bean 名称），可为 null
         * @param <O>       汇聚输出类型
         * @return 完整的并行 {@link Flow} 实例
         * @throws NullPointerException 当 {@code joinClass} 为 null 时抛出
         */
        public <O> Flow<I, O> joinContextual(Class<? extends ContextualJoinStrategy<? super I, O>> joinClass, String qualifier) {
            Objects.requireNonNull(joinClass, "joinClass must not be null");
            List<Logical.ParallelBranch> logical = new ArrayList<Logical.ParallelBranch>();
            for (Branch<I, ?> branch : branches) {
                logical.add(new Logical.ParallelBranch(branch, branch.flow().root));
            }
            return new Flow<I, O>(new Logical.Parallel(logical, binding(joinClass, qualifier, Logical.BindingKind.CONTEXTUAL_JOIN)));
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
     * 导出当前逻辑流程的结构化只读描述模型。
     *
     * @param flowId 可选的流程唯一标识，可为 null
     * @return 流程结构描述模型 {@link FlowDescription}
     */
    public FlowDescription describe(String flowId) {
        return new FlowDescription(flowId, com.team4u.framework.flow.desc.FlowDescriptionBuilder.describe(root, com.team4u.framework.flow.compiler.FlowPaths.root()));
    }

    /**
     * 使用默认的拒绝解析器（{@link OperationResolver#rejecting()}）将当前流程校验并投影为目标运行时拓扑结构。
     *
     * @param visitor 可执行流访问者 SPI，不能为 null
     * @param <R>     投影结果类型
     * @return 访问者投影生成的实例
     */
    public <R> R project(ExecutableFlowVisitor<R> visitor) {
        return project(BindingResolver.rejecting(), visitor);
    }

    /**
     * 使用指定的 {@link BindingResolver} 将当前流程校验并解析后，通过 {@link ExecutableFlowVisitor} 投影为目标运行时拓扑结构。
     *
     * @param resolver 组件解析器，不能为 null
     * @param visitor  可执行流访问者 SPI，不能为 null
     * @param <R>      投影结果类型
     * @return 访问者投影生成的实例
     * @throws NullPointerException 当参数为 null 时抛出
     * @throws FlowBuildException   当流程结构校验失败时抛出
     */
    public <R> R project(BindingResolver resolver, ExecutableFlowVisitor<R> visitor) {
        Objects.requireNonNull(visitor, "visitor must not be null");
        Objects.requireNonNull(resolver, "resolver must not be null");
        Compiler.Compiled compiled = Compiler.compile(this, resolver);
        return ExecutableProjector.project(compiled.root(), visitor);
    }

    /**
     * Use 链式构建器的投影阶段（Project Stage）。
     *
     * @param <I> 流程根输入类型
     * @param <O> 当前流程输出类型
     * @param <P> 步骤操作入参类型
     * @param <R> 步骤操作返回值类型
     */
    public interface UseProjectStage<I, O, P, R> {
        /**
         * 声明从当前流程输出中提取入参的投影函数。
         *
         * @param projector 入参投影函数，不能为 null
         * @return 合并阶段构建器 {@link UseMergeStage}
         */
        UseMergeStage<I, O, P, R> project(Function<? super O, ? extends P> projector);
    }

    /**
     * Use 链式构建器的合并与修饰阶段（Merge Stage）。
     *
     * @param <I> 流程根输入类型
     * @param <O> 当前流程输出类型
     * @param <P> 步骤操作入参类型
     * @param <R> 步骤操作返回值类型
     */
    public interface UseMergeStage<I, O, P, R> {
        /**
         * 以合并函数终结当前步骤，将当前状态与步骤 Accepted 返回值合并为新的输出类型。
         *
         * @param merger 结果合并函数，不能为 null
         * @param <N>    合并后的新输出类型
         * @return 串联当前步骤后的新 {@link Flow} 实例
         */
        <N> Flow<I, N> merge(BiFunction<? super O, ? super R, ? extends N> merger);

        /**
         * 丢弃步骤返回值终结当前步骤：执行该步骤并校验其四态，在 Accepted 时继续保留原输入状态向前传递。
         *
         * @return 串联当前步骤后的新 {@link Flow} 实例
         */
        Flow<I, O> discardResult();

        /**
         * 以步骤返回值完全替代原状态终结当前步骤：步骤 Accepted 返回值成为后续流程的新输入。
         *
         * @return 串联当前步骤后的新 {@link Flow} 实例
         */
        Flow<I, R> replaceWithResult();

        /**
         * 为当前步骤附加命名修饰器。
         *
         * @param name 步骤名称，不能为 null
         * @return 附加修饰器后的新不可变构建阶段
         */
        UseMergeStage<I, O, P, R> named(String name);

        /**
         * 为当前步骤附加超时修饰器。
         *
         * @param duration 超时时长，不能为 null
         * @return 附加修饰器后的新不可变构建阶段
         */
        UseMergeStage<I, O, P, R> timeout(Duration duration);
    }

    private static final class UseProjectStageImpl<I, O, P, R> implements UseProjectStage<I, O, P, R> {
        private final Flow<I, O> parent;
        private final Logical.Binding binding;

        UseProjectStageImpl(Flow<I, O> parent, Logical.Binding binding) {
            this.parent = parent;
            this.binding = binding;
        }

        @Override
        public UseMergeStage<I, O, P, R> project(Function<? super O, ? extends P> projector) {
            Objects.requireNonNull(projector, "projector must not be null");
            return new UseMergeStageImpl<I, O, P, R>(parent, binding, projector, Collections.<Function<Logical, Logical>>emptyList());
        }
    }

    private static final class UseMergeStageImpl<I, O, P, R> implements UseMergeStage<I, O, P, R> {
        private final Flow<I, O> parent;
        private final Logical.Binding binding;
        private final Function<? super O, ? extends P> projector;
        private final List<Function<Logical, Logical>> modifiers;

        UseMergeStageImpl(Flow<I, O> parent,
                          Logical.Binding binding,
                          Function<? super O, ? extends P> projector,
                          List<Function<Logical, Logical>> modifiers) {
            this.parent = parent;
            this.binding = binding;
            this.projector = projector;
            this.modifiers = modifiers;
        }

        @Override
        public UseMergeStage<I, O, P, R> named(String name) {
            Objects.requireNonNull(name, "name must not be null");
            List<Function<Logical, Logical>> copy = new ArrayList<Function<Logical, Logical>>(modifiers);
            copy.add(logical -> new Logical.Named(text(name, "label"), logical));
            return new UseMergeStageImpl<I, O, P, R>(parent, binding, projector, Collections.unmodifiableList(copy));
        }

        @Override
        public UseMergeStage<I, O, P, R> timeout(Duration duration) {
            final Duration validDuration = requireValidTimeout(duration);
            List<Function<Logical, Logical>> copy = new ArrayList<Function<Logical, Logical>>(modifiers);
            copy.add(logical -> new Logical.Control(Logical.Control.Kind.TIMEOUT, logical, null, Function.identity(), validDuration));
            return new UseMergeStageImpl<I, O, P, R>(parent, binding, projector, Collections.unmodifiableList(copy));
        }

        @Override
        public Flow<I, O> discardResult() {
            return merge((state, ignored) -> state);
        }

        @Override
        public Flow<I, R> replaceWithResult() {
            return merge((ignored, result) -> result);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <N> Flow<I, N> merge(BiFunction<? super O, ? super R, ? extends N> merger) {
            Objects.requireNonNull(merger, "merger must not be null");
            Function<Object, Object> erasedProject = value -> projector.apply((O) value);
            BiFunction<Object, Object, Object> erasedMerge = (state, res) -> merger.apply((O) state, (R) res);
            Logical node = new Logical.Invoke(binding, erasedProject, erasedMerge);
            for (Function<Logical, Logical> mod : modifiers) {
                node = mod.apply(node);
            }
            return new Flow<I, N>(sequence(parent.root, node));
        }
    }

    /**
     * 条件分支构建器（when/otherwise 便捷 API）。
     *
     * @param <I> 流程根输入类型
     * @param <O> 当前流程输入类型
     * @param <N> 条件分支输出类型
     */
    public static final class WhenBuilder<I, O, N> {
        private final Flow<I, O> parent;
        private final List<WhenBranch<O, N>> branches;

        WhenBuilder(Flow<I, O> parent, List<WhenBranch<O, N>> branches) {
            this.parent = parent;
            this.branches = branches;
        }

        private Logical buildLogical(Logical otherwiseNode) {
            Logical currentOtherwise = otherwiseNode;
            for (int i = branches.size() - 1; i >= 0; i--) {
                WhenBranch<O, N> wb = branches.get(i);
                Predicate<? super O> pred = wb.predicate;
                @SuppressWarnings("unchecked")
                Operation<O, Boolean> selectorOp = (ctx, in) -> Outcome.accepted(pred.test(in));
                Logical.Binding selectorBinding = binding(selectorOp, Logical.BindingKind.OPERATION);
                Logical.Route.Case trueCase = new Logical.Route.Case(Boolean.TRUE, wb.branch.root);
                currentOtherwise = new Logical.Route(selectorBinding, Collections.singletonList(trueCase), currentOtherwise);
            }
            return sequence(parent.root, currentOtherwise);
        }

        /**
         * 追加新的备选条件分支（按声明顺序匹配，首个命中者执行）。
         *
         * @param predicate 判定谓词，不能为 null
         * @param branch    命中执行的分支流程，不能为 null
         * @return 包含新分支的构建器
         */
        public WhenBuilder<I, O, N> when(Predicate<? super O> predicate, Flow<O, N> branch) {
            Objects.requireNonNull(predicate, "predicate must not be null");
            Objects.requireNonNull(branch, "branch must not be null");
            List<WhenBranch<O, N>> copy = new ArrayList<WhenBranch<O, N>>(branches);
            copy.add(new WhenBranch<O, N>(predicate, branch));
            return new WhenBuilder<I, O, N>(parent, Collections.unmodifiableList(copy));
        }

        /**
         * 显式指定全部条件均不满足时的兜底分支流程。
         *
         * <p>若需要未命中时按恒等透传（保持输入原样透传），可显式传入 {@code otherwise(Flow.identity())}，
         * 此时编译器将通过泛型强类型约束确保分支输出类型 {@code N} 与输入类型 {@code O} 严格一致，消除泛型不安全漏洞。</p>
         *
         * @param branch 兜底分支流程，不能为 null
         * @return 绑定兜底分支后的 Flow 实例
         */
        public Flow<I, N> otherwise(Flow<O, N> branch) {
            Objects.requireNonNull(branch, "otherwise branch must not be null");
            return new Flow<I, N>(buildLogical(branch.root));
        }
    }

    private static final class WhenBranch<O, N> {
        final Predicate<? super O> predicate;
        final Flow<O, N> branch;

        WhenBranch(Predicate<? super O> predicate, Flow<O, N> branch) {
            this.predicate = predicate;
            this.branch = branch;
        }
    }

    /**
     * 基于结构化并行与保序上下文汇聚的状态填充流水线构建器（parallelFill 便捷 API）。
     *
     * <p><strong>Merger 契约约束（确定性与重放安全）：</strong>
     * 各分支的 {@code merge} 函数应当满足：
     * <ul>
     *   <li>确定性计算（deterministic）且重放安全（replay-safe）；</li>
     *   <li>严禁执行发外部消息、写外部数据库等产生外部副作用的操作；</li>
     *   <li>推荐使用不可变状态拷贝，但允许就地变更（in-place mutation）；注意当后置 merge 发生异常时，
     *   流程虽返回 Failed 失败状态，但无法提供跨属性的事务性回滚保证。</li>
     * </ul>
     * </p>
     *
     * @param <I> 流程根输入类型
     * @param <O> 当前待填充的状态数据类型
     */
    public static final class ParallelFillBuilder<I, O> {
        private final Flow<I, O> parent;
        private final List<ForkEntry<O, ?>> forks;
        private final Duration timeout;

        private ParallelFillBuilder(Flow<I, O> parent) {
            this(parent, Collections.<ForkEntry<O, ?>>emptyList(), null);
        }

        private ParallelFillBuilder(Flow<I, O> parent, List<ForkEntry<O, ?>> forks, Duration timeout) {
            this.parent = parent;
            this.forks = forks;
            this.timeout = timeout;
        }

        /**
         * 追加一个并发填充计算分支。
         *
         * @param project   从状态提取入参的投影函数，不能为 null
         * @param operation 并发执行的业务操作实例，不能为 null
         * @param merge     保序合并计算结果到状态的合并函数，不能为 null
         * @param <P>       操作入参类型
         * @param <R>       操作返回值类型
         * @return 包含新分支的构建器
         */
        public <P, R> ParallelFillBuilder<I, O> fork(
                Function<? super O, ? extends P> project,
                Operation<P, R> operation,
                BiFunction<? super O, ? super R, ? extends O> merge) {
            Objects.requireNonNull(project, "project must not be null");
            Objects.requireNonNull(operation, "operation must not be null");
            Objects.requireNonNull(merge, "merge must not be null");
            List<ForkEntry<O, ?>> copy = new ArrayList<ForkEntry<O, ?>>(forks);
            copy.add(new ForkEntry<O, R>(project, binding(operation, Logical.BindingKind.OPERATION), merge));
            return new ParallelFillBuilder<I, O>(parent, Collections.unmodifiableList(copy), timeout);
        }

        /**
         * 追加一个并发填充计算分支（延迟解析操作 Class）。
         *
         * @param project        从状态提取入参的投影函数，不能为 null
         * @param operationClass 并发执行的业务操作契约 Class，不能为 null
         * @param merge          保序合并计算结果到状态的合并函数，不能为 null
         * @param <P>            操作入参类型
         * @param <R>            操作返回值类型
         * @return 包含新分支的构建器
         */
        public <P, R> ParallelFillBuilder<I, O> fork(
                Function<? super O, ? extends P> project,
                Class<? extends Operation<P, R>> operationClass,
                BiFunction<? super O, ? super R, ? extends O> merge) {
            return fork(project, operationClass, null, merge);
        }

        /**
         * 追加一个并发填充计算分支（延迟解析操作 Class 与限定符）。
         *
         * @param project        从状态提取入参的投影函数，不能为 null
         * @param operationClass 并发执行的业务操作契约 Class，不能为 null
         * @param qualifier      Spring/Bean 限定符名称，可为 null
         * @param merge          保序合并计算结果到状态的合并函数，不能为 null
         * @param <P>            操作入参类型
         * @param <R>            操作返回值类型
         * @return 包含新分支的构建器
         */
        public <P, R> ParallelFillBuilder<I, O> fork(
                Function<? super O, ? extends P> project,
                Class<? extends Operation<P, R>> operationClass,
                String qualifier,
                BiFunction<? super O, ? super R, ? extends O> merge) {
            Objects.requireNonNull(project, "project must not be null");
            Objects.requireNonNull(operationClass, "operationClass must not be null");
            Objects.requireNonNull(merge, "merge must not be null");
            List<ForkEntry<O, ?>> copy = new ArrayList<ForkEntry<O, ?>>(forks);
            copy.add(new ForkEntry<O, R>(project, binding(operationClass, qualifier, Logical.BindingKind.OPERATION), merge));
            return new ParallelFillBuilder<I, O>(parent, Collections.unmodifiableList(copy), timeout);
        }

        /**
         * 为整个并发填充节点配置全局超时约束。
         *
         * @param duration 超时时长，不能为 null
         * @return 附加超时后的构建器
         */
        public ParallelFillBuilder<I, O> timeout(Duration duration) {
            duration = requireValidTimeout(duration);
            return new ParallelFillBuilder<I, O>(parent, forks, duration);
        }

        /**
         * 终结并发分支声明，基于 Parallel 与 ContextualJoinStrategy 合成状态填充流水线。
         *
         * @return 串联状态填充后的 Flow 实例
         * @throws FlowBuildException 当未声明任何 fork 分支时抛出
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Flow<I, O> end() {
            if (forks.isEmpty()) {
                throw new FlowBuildException(Collections.singletonList(new FlowBuildException.Problem(
                        "INVALID_PARALLEL_FILL", "$", "parallelFill requires at least one fork")));
            }
            List<Branch<O, ?>> branches = new ArrayList<Branch<O, ?>>(forks.size());
            for (int i = 0; i < forks.size(); i++) {
                ForkEntry<O, ?> fork = forks.get(i);
                Branch<O, ?> branch = createForkBranch(i, fork);
                branches.add(branch);
            }
            com.team4u.framework.flow.api.ContextualJoinStrategy<O, O> contextualJoin = (initialInput, results) -> {
                // 四态保序检查：按声明顺序检查各分支
                for (int i = 0; i < branches.size(); i++) {
                    Outcome<?> outcome = results.outcome(branches.get(i));
                    if (!(outcome instanceof Outcome.Accepted)) {
                        return (Outcome<O>) outcome;
                    }
                }
                // 保序状态折叠
                O current = initialInput;
                for (int i = 0; i < forks.size(); i++) {
                    ForkEntry<O, Object> fork = (ForkEntry<O, Object>) forks.get(i);
                    Outcome.Accepted<Object> accepted = (Outcome.Accepted<Object>) results.outcome(branches.get(i));
                    current = fork.merger.apply(current, accepted.value());
                }
                return Outcome.accepted(current);
            };

            Branch<O, ?>[] branchArray = branches.toArray(new Branch[0]);
            Flow<O, O> parallelFlow = Flow.parallel(branchArray).join(contextualJoin);
            if (timeout != null) {
                parallelFlow = parallelFlow.timeout(timeout);
            }
            return parent.then(parallelFlow);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static <O, P, R> Branch<O, ?> createForkBranch(int index, ForkEntry<O, ?> fork) {
            ForkEntry<O, R> typed = (ForkEntry<O, R>) fork;
            Function<O, P> project = (Function<O, P>) typed.project;
            Flow<O, R> branchFlow = Flow.<O, P, R, R>invoke(
                    typed.binding,
                    project,
                    (ignored, r) -> r
            );
            return Branch.of("fork:" + index, branchFlow);
        }

        private static final class ForkEntry<O, R> {
            final Function<? super O, ?> project;
            final Logical.Binding binding;
            final BiFunction<? super O, ? super R, ? extends O> merger;

            ForkEntry(Function<? super O, ?> project, Logical.Binding binding, BiFunction<? super O, ? super R, ? extends O> merger) {
                this.project = project;
                this.binding = binding;
                this.merger = merger;
            }
        }
    }
}

