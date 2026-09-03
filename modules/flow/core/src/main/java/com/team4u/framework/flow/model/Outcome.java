package com.team4u.framework.flow.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;
import java.util.function.Function;
import com.team4u.framework.flow.api.Policy;

/**
 * 业务逻辑执行的严格四态结果代数类型：Accepted（携带有效输出）、Rejected（业务拒绝）、Skipped（弃权/跳过）、Failed（执行失败）。
 *
 * <p>设计与语义规则：
 * <ul>
 *   <li><b>四态封闭性</b>：采用 Java 8 闭集设计，构造器包级私有，仅允许框架内置的四种状态子类（{@link Accepted}、{@link Rejected}、{@link Skipped}、{@link Failed}），禁止外部继承。</li>
 *   <li><b>载荷差异</b>：
 *     <ul>
 *       <li>仅 {@link Accepted} 携带非 null 业务输出载荷（{@code value}）；</li>
 *       <li>{@link Rejected} 携带非 null 业务拒绝诊断 {@link Reason}，表示业务规则不满足或被策略拦截，不属于系统异常，会触发降级或短路；</li>
 *       <li>{@link Skipped} 携带非 null 弃权诊断 {@link Reason}，表示无适用路由或条件不匹配，支持在 {@code firstApplicable} 等组合中尝试下一分支；</li>
 *       <li>{@link Failed} 携带非 null 失败诊断 {@link Failure}，表示系统/运行时异常或策略判定故障，可触发重试或失败恢复（{@code recoverWith}）。</li>
 *     </ul>
 *   </li>
 *   <li><b>传播与映射行为</b>：在流水线编排中，仅 {@link Accepted} 状态会驱动后续节点执行；其余三种非成功态会保留当前作用域的上下文并原样向上传播/短路，直到命中对应的恢复机制（如 Fallback/Policy）或作为最终结果输出。</li>
 * </ul>
 * </p>
 *
 * @param <T> 成功态时携带的输出载荷类型
 * @author jay.wu
 */
public abstract class Outcome<T> {

    /**
     * 包级私有构造器，限制子类继承仅限本包内部。
     */
    Outcome() { }

    /**
     * 创建一个携带指定输出值的成功状态（Accepted）。
     *
     * @param value 业务成功输出载荷，不能为 null
     * @param <T>   载荷类型
     * @return 携带该值的 {@link Accepted} 结果实例
     * @throws NullPointerException 当 {@code value} 为 null 时抛出
     */
    public static <T> Outcome<T> accepted(T value) {
        return new Accepted<T>(value);
    }

    /**
     * 创建一个携带拒绝原因的业务拒绝状态（Rejected）。
     *
     * @param reason 业务拒绝诊断信息，不能为 null
     * @param <T>    泛型类型参数
     * @return 携带该原因的 {@link Rejected} 结果实例
     * @throws NullPointerException 当 {@code reason} 为 null 时抛出
     */
    public static <T> Outcome<T> rejected(Reason reason) {
        return new Rejected<T>(reason);
    }

    /**
     * 创建一个携带跳过原因的弃权/跳过状态（Skipped）。
     *
     * @param reason 跳过/弃权诊断信息，不能为 null
     * @param <T>    泛型类型参数
     * @return 携带该原因的 {@link Skipped} 结果实例
     * @throws NullPointerException 当 {@code reason} 为 null 时抛出
     */
    public static <T> Outcome<T> skipped(Reason reason) {
        return new Skipped<T>(reason);
    }

    /**
     * 创建一个携带失败诊断信息的执行失败状态（Failed）。
     *
     * @param failure 执行失败诊断信息，不能为 null
     * @param <T>     泛型类型参数
     * @return 携带该失败信息的 {@link Failed} 结果实例
     * @throws NullPointerException 当 {@code failure} 为 null 时抛出
     */
    public static <T> Outcome<T> failed(Failure failure) {
        return new Failed<T>(failure);
    }

    /**
     * 对当前结果的值应用转换函数。
     *
     * <p>转换规则：
     * <ul>
     *   <li>若当前为 {@link Accepted}，则对其载荷应用 {@code mapper} 函数并封装为新的 {@link Accepted}；</li>
     *   <li>若当前为 {@link Rejected}、{@link Skipped} 或 {@link Failed}，则原样保留诊断信息并安全转换类型参数；</li>
     * </ul>
     * </p>
     *
     * @param mapper 转换函数，当当前结果为 Accepted 时被调用，不能为 null
     * @param <R>    转换后的新结果类型
     * @return 转换后的 {@link Outcome} 实例
     * @throws NullPointerException 当 {@code mapper} 为 null 时抛出
     * @throws IllegalStateException 当遇到未知的 Outcome 子类实现时抛出
     */
    public <R> Outcome<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        if (this instanceof Accepted) {
            return accepted(mapper.apply(((Accepted<T>) this).value()));
        } else if (this instanceof Rejected) {
            return rejected(((Rejected<T>) this).reason());
        } else if (this instanceof Skipped) {
            return skipped(((Skipped<T>) this).reason());
        } else if (this instanceof Failed) {
            return failed(((Failed<T>) this).failure());
        }
        throw new IllegalStateException("Unknown Outcome: " + getClass());
    }

    /**
     * 获取当前结果的种类枚举。
     *
     * @return 结果种类枚举 {@link Kind}
     * @throws IllegalStateException 当遇到未知的 Outcome 子类实现时抛出
     */
    public Kind kind() {
        if (this instanceof Accepted) {
            return Kind.ACCEPTED;
        } else if (this instanceof Rejected) {
            return Kind.REJECTED;
        } else if (this instanceof Skipped) {
            return Kind.SKIPPED;
        } else if (this instanceof Failed) {
            return Kind.FAILED;
        }
        throw new IllegalStateException("Unknown Outcome: " + getClass());
    }

    /**
     * 当前结果是否为成功态（Accepted）。
     *
     * @return 若为 Accepted 返回 true，否则返回 false
     */
    public boolean isAccepted() {
        return this instanceof Accepted;
    }

    /**
     * 当前结果是否为业务拒绝态（Rejected）。
     *
     * @return 若为 Rejected 返回 true，否则返回 false
     */
    public boolean isRejected() {
        return this instanceof Rejected;
    }

    /**
     * 当前结果是否为弃权跳过态（Skipped）。
     *
     * @return 若为 Skipped 返回 true，否则返回 false
     */
    public boolean isSkipped() {
        return this instanceof Skipped;
    }

    /**
     * 当前结果是否为执行失败态（Failed）。
     *
     * @return 若为 Failed 返回 true，否则返回 false
     */
    public boolean isFailed() {
        return this instanceof Failed;
    }

    /**
     * Outcome 状态类型枚举。
     */
    public enum Kind {
        /** 成功态，携带有效输出。 */
        ACCEPTED,
        /** 业务拒绝态，携带拒绝诊断原因。 */
        REJECTED,
        /** 弃权/跳过态，携带跳过诊断原因。 */
        SKIPPED,
        /** 失败态，携带故障/异常诊断信息。 */
        FAILED
    }

    /**
     * 成功态：业务逻辑执行成功，携带非 null 的输出载荷。
     *
     * @param <T> 输出载荷类型
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    public static final class Accepted<T> extends Outcome<T> {
        private final T value;

        Accepted(T value) {
            this.value = Objects.requireNonNull(value, "accepted value must not be null");
        }
    }

    /**
     * 业务拒绝态：业务规则校验不通过或被 Policy 拦截，携带稳定诊断 {@link Reason}。
     * 不会进入系统的失败恢复，而是触发降级或短路。
     *
     * @param <T> 泛型类型参数
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    public static final class Rejected<T> extends Outcome<T> {
        private final Reason reason;

        Rejected(Reason reason) {
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /**
     * 弃权/跳过态：当前分支不适用或被跳过，携带稳定诊断 {@link Reason}。
     * 常用于 {@code firstApplicable} 等多分支尝试场景。
     *
     * @param <T> 泛型类型参数
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    public static final class Skipped<T> extends Outcome<T> {
        private final Reason reason;

        Skipped(Reason reason) {
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /**
     * 执行失败态：步骤抛出异常或策略判定不可恢复故障，携带诊断 {@link Failure}。
     * 可触发重试机制或失败恢复（{@code recoverWith}）。
     *
     * @param <T> 泛型类型参数
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    public static final class Failed<T> extends Outcome<T> {
        private final Failure failure;

        Failed(Failure failure) {
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
        }
    }
}
