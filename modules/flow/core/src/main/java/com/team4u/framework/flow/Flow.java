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

/**
 * 不可变的类型化逻辑 Flow。仅描述结构，本身不可执行；需通过 {@link Local} 显式投影。
 * 所有组合方法返回新的 Flow 实例，不修改当前对象。
 */
public final class Flow<I, O> {
    private final Logical root;

    private Flow(Logical root) {
        this.root = Objects.requireNonNull(root, "root must not be null");
    }

    /** 以单个 Operation 构造最简 Flow。 */
    public static <I, O> Flow<I, O> step(Operation<I, O> operation) {
        return Flow.<I, I, O, O>invoke(binding(operation, Logical.BindingKind.OPERATION),
                value -> value, (ignored, value) -> value);
    }

    public static <I, O> Flow<I, O> step(
            Class<? extends Operation<I, O>> operationClass) {
        return step(operationClass, null);
    }

    public static <I, O> Flow<I, O> step(
            Class<? extends Operation<I, O>> operationClass, String qualifier) {
        return Flow.<I, I, O, O>invoke(
                binding(operationClass, qualifier, Logical.BindingKind.OPERATION),
                value -> value, (ignored, value) -> value);
    }

    public <N> Flow<I, N> then(Operation<O, N> operation) {
        return then(Flow.step(operation));
    }

    public <N> Flow<I, N> then(Class<? extends Operation<O, N>> operationClass) {
        return then(Flow.step(operationClass));
    }

    public <N> Flow<I, N> then(
            Class<? extends Operation<O, N>> operationClass, String qualifier) {
        return then(Flow.step(operationClass, qualifier));
    }

    /** 顺序拼接：当前 Flow 的输出作为 {@code next} 的输入。 */
    public <N> Flow<I, N> then(Flow<O, N> next) {
        Objects.requireNonNull(next, "next must not be null");
        return new Flow<I, N>(sequence(root, next.root));
    }

    /** 调用 Operation 并合并结果：{@code project} 从当前输出派生入参，
     * {@code merge} 将原输出与 Operation 输出合并为新输出。 */
    public <P, R, N> Flow<I, N> use(Operation<P, R> operation,
                                     Function<? super O, ? extends P> project,
                                     BiFunction<? super O, ? super R, ? extends N> merge) {
        return then(invoke(binding(operation, Logical.BindingKind.OPERATION), project, merge));
    }

    public <P, R, N> Flow<I, N> use(
            Class<? extends Operation<P, R>> operationClass,
            Function<? super O, ? extends P> project,
            BiFunction<? super O, ? super R, ? extends N> merge) {
        return use(operationClass, null, project, merge);
    }

    public <P, R, N> Flow<I, N> use(
            Class<? extends Operation<P, R>> operationClass, String qualifier,
            Function<? super O, ? extends P> project,
            BiFunction<? super O, ? super R, ? extends N> merge) {
        return then(invoke(binding(operationClass, qualifier, Logical.BindingKind.OPERATION),
                project, merge));
    }

    /** 以具名 scope 包裹 body，用于 Fallback 的恢复边界。 */
    public static <I, O> Flow<I, O> scope(String name, Flow<I, O> body) {
        Objects.requireNonNull(body, "body must not be null");
        return new Flow<I, O>(new Logical.Sequence(Collections.singletonList(body.root), text(name, "scope name")));
    }

    /** 为当前节点设置可观察标签。 */
    public Flow<I, O> named(String label) {
        return new Flow<I, O>(new Logical.Named(text(label, "label"), root));
    }

    /** 构造 Route 起点：运行 {@code selector} 得到路由键，再由后续
     * {@link RouteCases#caseOf caseOf}/{@link RouteCases#otherwise otherwise} 选择分支。 */
    public static <I, K> RouteStart<I, K> route(Operation<I, K> selector) {
        return new RouteStart<I, K>(binding(selector, Logical.BindingKind.OPERATION));
    }

    public static <I, K> RouteStart<I, K> route(
            Class<? extends Operation<I, K>> selectorClass) {
        return route(selectorClass, null);
    }

    public static <I, K> RouteStart<I, K> route(
            Class<? extends Operation<I, K>> selectorClass, String qualifier) {
        return new RouteStart<I, K>(binding(selectorClass, qualifier, Logical.BindingKind.OPERATION));
    }

    /**
     * firstApplicable：依次尝试各分支，首个非 Skipped 的 Outcome 即为结果；
     * 全部 Skipped 则结果为 Skipped。
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

    /** 失败恢复：当前 Flow 若 Failed，则以 {@link Recovery}（携带原始输入与失败）作为 fallback 的输入。 */
    public Flow<I, O> recoverWith(Flow<Recovery<I>, O> fallback) {
        Objects.requireNonNull(fallback, "fallback must not be null");
        return new Flow<I, O>(new Logical.Fallback(Logical.Fallback.Trigger.FAILED,
                Arrays.asList(root, fallback.root)));
    }

    /** 在当前 Flow 后追加挂起点；恢复时输出 {@link Resumed}（原状态值 + 恢复信号）。 */
    public <R> Flow<I, Resumed<O, R>> await(ResumePoint<R> point) {
        return then(new Flow<O, Resumed<O, R>>(new Logical.Await(
                Objects.requireNonNull(point, "point must not be null"))));
    }

    public <K> Flow<I, O> policy(Policy<K> policy,
                                  Function<? super I, ? extends K> keyProjection) {
        return control(Logical.Control.Kind.POLICY,
                binding(policy, Logical.BindingKind.POLICY), keyProjection, null);
    }

    public <K> Flow<I, O> policy(Class<? extends Policy<K>> policyClass,
                                  Function<? super I, ? extends K> keyProjection) {
        return policy(policyClass, null, keyProjection);
    }

    public <K> Flow<I, O> policy(Class<? extends Policy<K>> policyClass, String qualifier,
                                  Function<? super I, ? extends K> keyProjection) {
        return control(Logical.Control.Kind.POLICY,
                binding(policyClass, qualifier, Logical.BindingKind.POLICY), keyProjection, null);
    }

    /** 附加可跨重启持久化的 PersistentPolicy；其状态会在 Durable 快照中保留。不能用于 Parallel 分支。 */
    public <K, S> Flow<I, O> persistentPolicy(PersistentPolicy<K, S> policy,
                                               Function<? super I, ? extends K> keyProjection) {
        return control(Logical.Control.Kind.PERSISTENT_POLICY,
                binding(policy, Logical.BindingKind.PERSISTENT_POLICY), keyProjection, null);
    }

    public <K, S> Flow<I, O> persistentPolicy(
            Class<? extends PersistentPolicy<K, S>> policyClass,
            Function<? super I, ? extends K> keyProjection) {
        return persistentPolicy(policyClass, null, keyProjection);
    }

    public <K, S> Flow<I, O> persistentPolicy(
            Class<? extends PersistentPolicy<K, S>> policyClass, String qualifier,
            Function<? super I, ? extends K> keyProjection) {
        return control(Logical.Control.Kind.PERSISTENT_POLICY,
                binding(policyClass, qualifier, Logical.BindingKind.PERSISTENT_POLICY),
                keyProjection, null);
    }

    /** 失败重试：在 backoff 间隔后重新执行 body，最多 {@code retry.maxAttempts()} 次。 */
    public Flow<I, O> retry(Retry retry) {
        return control(Logical.Control.Kind.RETRY, null, Function.identity(),
                Objects.requireNonNull(retry, "retry must not be null"));
    }

    /** 为当前作用域设置截止时间，超时产生 TIMEOUT 失败并终止最近的作用域。 */
    public Flow<I, O> timeout(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
        return control(Logical.Control.Kind.TIMEOUT, null, Function.identity(), duration);
    }

    /** 直接透传输入的 identity Flow。 */
    public static <T> Flow<T, T> identity() {
        return new Flow<T, T>(new Logical.Complete(null, true));
    }

    public static <I, O> Flow<I, O> accepted(O value) {
        return complete(Outcome.accepted(value));
    }

    public static <I, O> Flow<I, O> rejected(Reason reason) {
        return complete(Outcome.rejected(reason));
    }

    public static <I, O> Flow<I, O> skipped(Reason reason) {
        return complete(Outcome.skipped(reason));
    }

    public static <I, O> Flow<I, O> failed(Failure failure) {
        return complete(Outcome.failed(failure));
    }

    /** 并行起点：声明若干 Branch，后续通过 {@link ParallelBuilder#join join} 指定汇合策略。 */
    @SafeVarargs
    public static <I> ParallelBuilder<I> parallel(Branch<I, ?>... branches) {
        return new ParallelBuilder<I>(Arrays.asList(branches));
    }

    Logical root() {
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

    /** Route 构建的起点，持有 selector 绑定，待 {@link RouteCases} 补充分支。 */
    public static final class RouteStart<I, K> {
        private final Logical.Binding selector;

        private RouteStart(Logical.Binding selector) {
            this.selector = selector;
        }

        public <O> RouteCases<I, K, O> caseOf(K key, Flow<I, O> branch) {
            return new RouteCases<I, K, O>(selector, Collections.emptyList()).caseOf(key, branch);
        }

        public <O> Flow<I, O> otherwise(Flow<I, O> branch) {
            return new Flow<I, O>(new Logical.Route(selector, Collections.emptyList(),
                    Objects.requireNonNull(branch, "branch must not be null").root));
        }
    }

    /** Route 分支集合，支持链式 {@link #caseOf caseOf} 与终结 {@link #otherwise otherwise}/{@link #withoutOtherwise}。 */
    public static final class RouteCases<I, K, O> {
        private final Logical.Binding selector;
        private final List<Logical.Route.Case> cases;

        private RouteCases(Logical.Binding selector, List<Logical.Route.Case> cases) {
            this.selector = selector;
            this.cases = cases;
        }

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

        public Flow<I, O> otherwise(Flow<I, O> branch) {
            return new Flow<I, O>(new Logical.Route(selector, cases,
                    Objects.requireNonNull(branch, "branch must not be null").root));
        }

        public Flow<I, O> withoutOtherwise() {
            return new Flow<I, O>(new Logical.Route(selector, cases, null));
        }
    }

    /**
     * Parallel 构建器：校验分支名称唯一，最终通过 {@link #join} 与 JoinStrategy 合成 Parallel 节点。
     * 分支不能包含 await 或 PersistentPolicy（由 Compiler 校验）。
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

        public <O> Flow<I, O> join(JoinStrategy<O> join) {
            Objects.requireNonNull(join, "join must not be null");
            List<Logical.ParallelBranch> logical = new ArrayList<Logical.ParallelBranch>();
            for (Branch<I, ?> branch : branches) {
                logical.add(new Logical.ParallelBranch(branch, branch.flow().root));
            }
            return new Flow<I, O>(new Logical.Parallel(logical, join));
        }
    }

    /** 导出结构化只读描述模型。 */
    public FlowDescription describe() {
        return describe(null);
    }

    /** 导出结构化只读描述模型。 */
    public FlowDescription describe(String flowId) {
        return new FlowDescription(flowId, FlowDescriptionBuilder.describe(root, "$"));
    }

    /**
     * 将当前 Flow 校验并解析绑定后，通过 {@link ExecutableFlowVisitor} 导出强类型可执行投影。
     */
    public <R> R project(ExecutableFlowVisitor<R> visitor) {
        return project(OperationResolver.rejecting(), visitor);
    }

    /**
     * 使用指定 {@link OperationResolver} 将当前 Flow 校验并解析绑定后，
     * 通过 {@link ExecutableFlowVisitor} 导出强类型可执行投影。
     */
    public <R> R project(OperationResolver resolver, ExecutableFlowVisitor<R> visitor) {
        Objects.requireNonNull(visitor, "visitor must not be null");
        Objects.requireNonNull(resolver, "resolver must not be null");
        Compiler.Compiled compiled = Compiler.compile(this, resolver);
        return ExecutableProjector.project(compiled.root(), visitor);
    }
}
