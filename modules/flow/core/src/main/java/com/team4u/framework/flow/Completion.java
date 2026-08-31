package com.team4u.framework.flow;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;
import java.util.Optional;

/**
 * 步骤完成状态摘要信息（不包含业务输出载荷）。
 *
 * <p>专门作为 {@link Policy#after} 与 {@link PersistentPolicy#after} 观察或后置决策时的入参。
 * 具有严格的状态一致性校验：
 * <ul>
 *   <li>{@link Outcome.Kind#ACCEPTED}：不含 {@code reason} 与 {@code failure}；</li>
 *   <li>{@link Outcome.Kind#REJECTED} 与 {@link Outcome.Kind#SKIPPED}：必须包含 {@link Reason}，且不含 {@link Failure}；</li>
 *   <li>{@link Outcome.Kind#FAILED}：必须包含 {@link Failure}，且不含 {@link Reason}。</li>
 * </ul>
 * </p>
 *
 * @author team4u
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class Completion {
    /** 步骤完成的结果种类。 */
    private final Outcome.Kind kind;
    /** 拒绝或跳过时的诊断原因（若状态为 REJECTED 或 SKIPPED 时存在）。 */
    private final Optional<Reason> reason;
    /** 失败时的故障诊断信息（若状态为 FAILED 时存在）。 */
    private final Optional<Failure> failure;

    /**
     * 构造完成状态摘要，并执行严格的状态-诊断信息一致性校验。
     *
     * @param kind    结果类型枚举，不能为 null
     * @param reason  诊断原因 Optional 容器，不能为 null
     * @param failure 故障信息 Optional 容器，不能为 null
     * @throws NullPointerException     当任何参数为 null 时抛出
     * @throws IllegalArgumentException 当诊断原因/故障信息与结果种类不匹配时抛出
     */
    public Completion(Outcome.Kind kind, Optional<Reason> reason, Optional<Failure> failure) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
        if ((kind == Outcome.Kind.REJECTED || kind == Outcome.Kind.SKIPPED) != reason.isPresent()) {
            throw new IllegalArgumentException("reason does not match outcome kind");
        }
        if ((kind == Outcome.Kind.FAILED) != failure.isPresent()) {
            throw new IllegalArgumentException("failure does not match outcome kind");
        }
    }

    /**
     * 从完整的 {@link Outcome} 提取不含载荷的完成摘要。
     *
     * @param outcome 原始四态结果，不能为 null
     * @return 对应的完成摘要实例
     * @throws IllegalStateException 当遇到未知的 Outcome 子类时抛出
     */
    static Completion from(Outcome<?> outcome) {
        if (outcome instanceof Outcome.Accepted) {
            return new Completion(Outcome.Kind.ACCEPTED, Optional.empty(), Optional.empty());
        } else if (outcome instanceof Outcome.Rejected) {
            return new Completion(Outcome.Kind.REJECTED, Optional.of(((Outcome.Rejected<?>) outcome).reason()), Optional.empty());
        } else if (outcome instanceof Outcome.Skipped) {
            return new Completion(Outcome.Kind.SKIPPED, Optional.of(((Outcome.Skipped<?>) outcome).reason()), Optional.empty());
        } else if (outcome instanceof Outcome.Failed) {
            return new Completion(Outcome.Kind.FAILED, Optional.empty(), Optional.of(((Outcome.Failed<?>) outcome).failure()));
        }
        throw new IllegalStateException("Unknown outcome: " + outcome);
    }
}

