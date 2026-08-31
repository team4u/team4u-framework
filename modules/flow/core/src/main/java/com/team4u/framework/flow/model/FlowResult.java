package com.team4u.framework.flow.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.api.ResumePoint;

/**
 * Local 内存模式下流程执行的严格三态结果代数模型：Completed（已执行完成）、Suspended（异步挂起）、Cancelled（已被取消）。
 *
 * <p>采用 Java 8 闭集设计，不可在模块外部实现或继承：
 * <ul>
 *   <li>{@link Completed}：执行已完成，携带最终的四态业务结果 {@link Outcome}（Accepted/Rejected/Skipped/Failed）；</li>
 *   <li>{@link Suspended}：执行遇到 {@code Await} 节点而暂停，携带单次消费的续接句柄 {@link Suspension}；</li>
 *   <li>{@link Cancelled}：执行被取消信号中断，保留执行实例唯一标识 {@code executionId}。</li>
 * </ul>
 * </p>
 *
 * @param <O> 流程最终执行成功时的输出载荷类型
 * @author jay.wu
 */
public abstract class FlowResult<O> {

    /**
     * 包级私有构造器，限制仅限本包内部继承。
     */
    FlowResult() { }

    /**
     * 创建已完成的流程结果实例。
     *
     * @param outcome 最终的四态业务结果，不能为 null
     * @param <O>     输出载荷类型
     * @return {@link Completed} 结果实例
     * @throws NullPointerException 当 {@code outcome} 为 null 时抛出
     */
    public static <O> FlowResult<O> completed(Outcome<O> outcome) {
        return new Completed<O>(outcome);
    }

    /**
     * 创建执行挂起的流程结果实例。
     *
     * @param suspension 单次消费的续接句柄，不能为 null
     * @param <O>        输出载荷类型
     * @return {@link Suspended} 结果实例
     * @throws NullPointerException 当 {@code suspension} 为 null 时抛出
     */
    public static <O> FlowResult<O> suspended(Suspension<O> suspension) {
        return new Suspended<O>(suspension);
    }

    /**
     * 创建执行被取消的流程结果实例。
     *
     * @param executionId 执行实例 ID，不能为 null
     * @param <O>         输出载荷类型
     * @return {@link Cancelled} 结果实例
     * @throws NullPointerException 当 {@code executionId} 为 null 时抛出
     */
    public static <O> FlowResult<O> cancelled(String executionId) {
        return new Cancelled<O>(executionId);
    }

    /**
     * 断言并提取最终的成功载荷值。
     *
     * <p>若当前结果不为 {@link Completed} 或其内部的 {@link Outcome} 不为 {@link Outcome.Accepted}，
     * 则直接抛出 {@link IllegalStateException}。</p>
     *
     * @return 业务执行成功的输出载荷值
     * @throws IllegalStateException 当流程未以 Accepted 态完成时抛出
     */
    public O requireAccepted() {
        if (this instanceof Completed) {
            Outcome<O> outcome = ((Completed<O>) this).outcome();
            if (outcome instanceof Outcome.Accepted) {
                return ((Outcome.Accepted<O>) outcome).value();
            }
        }
        throw new IllegalStateException("Flow did not complete with Accepted");
    }

    /**
     * 正常完成态：携带流程最终四态结果 {@link Outcome}。
     *
     * @param <O> 输出载荷类型
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    public static final class Completed<O> extends FlowResult<O> {
        /** 流程最终的四态业务结果。 */
        private final Outcome<O> outcome;

        /**
         * 构造 Completed 结果。
         *
         * @param outcome 业务结果，不能为 null
         */
        Completed(Outcome<O> outcome) {
            this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }

    /**
     * 异步挂起态：流程进入等待外部恢复状态，携带单次消费的续接句柄 {@link Suspension}。
     *
     * @param <O> 输出载荷类型
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    public static final class Suspended<O> extends FlowResult<O> {
        /** 挂起续接句柄。 */
        private final Suspension<O> suspension;

        /**
         * 构造 Suspended 结果。
         *
         * @param suspension 续接句柄，不能为 null
         */
        Suspended(Suspension<O> suspension) {
            this.suspension = Objects.requireNonNull(suspension, "suspension must not be null");
        }

        /**
         * 检查当前挂起是否正在等待指定的挂起点。
         *
         * @param point 挂起点标识，不能为 null
         * @return 若当前挂起点名称与 {@code point.name()} 匹配则返回 true，否则返回 false
         * @throws NullPointerException 当 {@code point} 为 null 时抛出
         */
        public boolean awaiting(ResumePoint<?> point) {
            return Objects.requireNonNull(point, "point must not be null").name()
                    .equals(suspension.resumePoint());
        }
    }

    /**
     * 取消态：流程因收到取消信号而提前终止，仅保留执行实例 ID。
     *
     * @param <O> 输出载荷类型
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    public static final class Cancelled<O> extends FlowResult<O> {
        /** 执行实例唯一 ID。 */
        private final String executionId;

        /**
         * 构造 Cancelled 结果。
         *
         * @param executionId 执行 ID，不能为 null
         */
        Cancelled(String executionId) {
            this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
        }
    }
}

