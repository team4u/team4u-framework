package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Outcome;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 耐久化执行命令执行结果封闭代数类型（Durable Execution Result Family）。
 *
 * <p>表示对 {@link DurableExecutable} 触发操作（start / resume / recover / cancel）后的返回结果，
 * 封装了最新的不可变快照信封（{@link DurableSnapshot}），派生四个强类型结果：
 * <ul>
 *   <li>{@link Completed}：执行完成，携带业务四态结果（{@link Outcome}）；</li>
 *   <li>{@link Suspended}：流程挂起，携带目标挂起点名称（{@code resumePoint}）；</li>
 *   <li>{@link Active}：流程仍处于活跃中，携带退避重试或定时唤醒时刻（{@code wakeAt}）；</li>
 *   <li>{@link Cancelled}：流程已被成功取消。</li>
 * </ul>
 * </p>
 *
 * @param <O> 流程成功完成时的业务载荷类型
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
public abstract class DurableResult<O> {
    /** 执行后落库的最新不可变快照信封。 */
    private final DurableSnapshot snapshot;

    DurableResult(DurableSnapshot snapshot, DurableLifecycle expected) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (snapshot.lifecycle() != expected) {
            throw new IllegalArgumentException("result requires " + expected
                    + " snapshot, but was " + snapshot.lifecycle());
        }
    }

    /**
     * 提取成功结果载荷；若非 {@link Completed} 或结果并非 {@link Outcome.Accepted} 则抛出状态异常。
     *
     * @return 成功载荷
     * @throws IllegalStateException 当执行未完成或未 Accepted 时抛出
     */
    public O requireAccepted() {
        if (this instanceof Completed) {
            Outcome<O> outcome = ((Completed<O>) this).outcome();
            if (outcome instanceof Outcome.Accepted) {
                return ((Outcome.Accepted<O>) outcome).value();
            }
        }
        throw new IllegalStateException(
                "Durable execution did not complete with Accepted");
    }

    /**
     * 流程执行完成终态结果。
     *
     * @param <O> 业务载荷泛型
     */
    @Getter
    @Accessors(fluent = true)
    public static final class Completed<O> extends DurableResult<O> {
        /** 流程最终产出的业务四态结果。 */
        private final Outcome<O> outcome;

        public Completed(Outcome<O> outcome, DurableSnapshot snapshot) {
            super(snapshot, DurableLifecycle.COMPLETED);
            this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }

    /**
     * 流程异步挂起结果。
     *
     * @param <O> 业务载荷泛型
     */
    @Getter
    @Accessors(fluent = true)
    public static final class Suspended<O> extends DurableResult<O> {
        /** 正在等待恢复的挂起点名称。 */
        private final String resumePoint;

        public Suspended(String resumePoint, DurableSnapshot snapshot) {
            super(snapshot, DurableLifecycle.SUSPENDED);
            this.resumePoint = text(resumePoint);
            if (!resumePoint.equals(snapshot.awaitingPoint())) {
                throw new IllegalArgumentException(
                        "resumePoint must match snapshot awaitingPoint");
            }
        }
    }

    /**
     * 流程活跃运行中（或处于退避等待）结果。
     *
     * @param <O> 业务载荷泛型
     */
    @Getter
    @Accessors(fluent = true)
    public static final class Active<O> extends DurableResult<O> {
        /** 计划唤醒时间戳（若在退避等待中）。 */
        private final Optional<Instant> wakeAt;

        public Active(Optional<Instant> wakeAt, DurableSnapshot snapshot) {
            super(snapshot, DurableLifecycle.ACTIVE);
            this.wakeAt = Objects.requireNonNull(wakeAt, "wakeAt must not be null");
        }
    }

    /**
     * 流程已被取消终态结果。
     *
     * @param <O> 业务载荷泛型
     */
    public static final class Cancelled<O> extends DurableResult<O> {
        public Cancelled(DurableSnapshot snapshot) {
            super(snapshot, DurableLifecycle.CANCELLED);
        }
    }

    private static String text(String value) {
        Objects.requireNonNull(value, "resumePoint must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(
                "resumePoint must not be blank");
        return value;
    }
}

