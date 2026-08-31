package com.team4u.framework.flow;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.Objects;

/**
 * 可持久化有状态治理策略核心接口 SPI（专为 Durable 持久化引擎与 Local 高级控制设计）。
 *
 * <p>策略状态 {@code S} 必须为不可变纯数据对象，由框架在每个阶段自动编码并保存到快照中，
 * 支持在进程崩溃或跨节点重启后恢复策略状态机。
 *
 * <p>阶段模型与裁决体系：
 * <ul>
 *   <li>{@link #initialState(Object)}：首次进入该控制作用域时生成策略初始状态；</li>
 *   <li>{@link #before(PolicyContext, Object, Object)}：前置准入判定，返回 {@link Before}（{@link Proceed} 放行、{@link WaitUntil} 延时等待、{@link Reject} 业务拒绝、{@link Fail} 故障拦截）；</li>
 *   <li>{@link #after(PolicyContext, Object, Object, Completion)}：后置完成处理，返回 {@link After}（{@link Return} 正常返回或 {@link RetryAt} 指定时刻唤醒重试）。</li>
 * </ul>
 * </p>
 *
 * @param <K> 策略键类型
 * @param <S> 策略不可变内部状态类型（需可被 StateMapper 序列化）
 * @author jay.wu
 */
public interface PersistentPolicy<K, S> {

    /**
     * 生成策略初始状态。
     *
     * @param key 策略键，保证非 null
     * @return 初始不可变状态对象，不能为 null
     */
    S initialState(K key);

    /**
     * 前置准入决策与状态变迁。
     *
     * @param context 策略执行上下文（元数据、重试轮次 attempt、取消信号），保证非 null
     * @param key     策略键，保证非 null
     * @param state   当前策略状态，保证非 null
     * @return 前置裁决对象 {@link Before}，不能返回 null
     * @throws Exception 当前置裁决逻辑异常时抛出
     */
    Before<S> before(PolicyContext context, K key, S state);

    /**
     * 后置完成观察与状态变迁决策。
     *
     * @param context    策略执行上下文，保证非 null
     * @param key        策略键，保证非 null
     * @param state      当前策略状态，保证非 null
     * @param completion 目标步骤完成摘要，保证非 null
     * @return 后置裁决对象 {@link After}，不能返回 null
     */
    After<S> after(PolicyContext context, K key, S state, Completion completion);

    /**
     * 前置准入判定的闭式代数结果父类。采用 Java 8 闭集设计，不可在外部继承。
     *
     * @param <S> 策略状态类型
     */
    abstract class Before<S> {
        Before() { }
    }

    /**
     * 放行决策：准许执行目标步骤，并携带推进后的新策略状态。
     *
     * @param <S> 策略状态类型
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    final class Proceed<S> extends Before<S> {
        /** 推进后的新状态。 */
        private final S state;

        Proceed(S state) {
            this.state = Objects.requireNonNull(state, "state must not be null");
        }
    }

    /**
     * 延时等待决策：推迟执行至指定绝对时刻（{@code instant}），携带推进后的新策略状态。
     *
     * @param <S> 策略状态类型
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    final class WaitUntil<S> extends Before<S> {
        /** 唤醒继续评估的绝对时间点。 */
        private final Instant instant;
        /** 推进后的新状态。 */
        private final S state;

        WaitUntil(Instant instant, S state) {
            this.instant = Objects.requireNonNull(instant, "instant must not be null");
            this.state = Objects.requireNonNull(state, "state must not be null");
        }
    }

    /**
     * 业务拒绝决策：拦截步骤执行，产生 Rejected 结果，并携带拒绝原因及状态。
     *
     * @param <S> 策略状态类型
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    final class Reject<S> extends Before<S> {
        /** 业务拒绝原因。 */
        private final Reason reason;
        /** 推进后的新状态。 */
        private final S state;

        Reject(Reason reason, S state) {
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
            this.state = Objects.requireNonNull(state, "state must not be null");
        }
    }

    /**
     * 故障拦截决策：拦截步骤执行，产生 Failed 结果，并携带故障诊断及状态。
     *
     * @param <S> 策略状态类型
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    final class Fail<S> extends Before<S> {
        /** 失败故障诊断。 */
        private final Failure failure;
        /** 推进后的新状态。 */
        private final S state;

        Fail(Failure failure, S state) {
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
            this.state = Objects.requireNonNull(state, "state must not be null");
        }
    }

    /**
     * 后置完成决策的闭式代数结果父类。采用 Java 8 闭集设计，不可在外部继承。
     *
     * @param <S> 策略状态类型
     */
    abstract class After<S> {
        After() { }
    }

    /**
     * 正常返回决策：结束当前轮次执行，并提交最终的策略状态。
     *
     * @param <S> 策略状态类型
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    final class Return<S> extends After<S> {
        /** 提交的最终状态。 */
        private final S state;

        Return(S state) {
            this.state = Objects.requireNonNull(state, "state must not be null");
        }
    }

    /**
     * 定时重试决策：指示流程在指定绝对时刻（{@code instant}）唤醒并重试执行目标步骤，携带更新的状态。
     *
     * @param <S> 策略状态类型
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    final class RetryAt<S> extends After<S> {
        /** 重试唤醒的绝对时间点。 */
        private final Instant instant;
        /** 推进后的新状态。 */
        private final S state;

        RetryAt(Instant instant, S state) {
            this.instant = Objects.requireNonNull(instant, "instant must not be null");
            this.state = Objects.requireNonNull(state, "state must not be null");
        }
    }

    /**
     * 创建放行前置决策。
     *
     * @param state 新策略状态，不能为 null
     * @param <S>   状态类型
     * @return {@link Proceed} 实例
     */
    static <S> Before<S> proceed(S state) {
        return new Proceed<S>(state);
    }

    /**
     * 创建延时等待前置决策。
     *
     * @param instant 唤醒绝对时间，不能为 null
     * @param state   新策略状态，不能为 null
     * @param <S>     状态类型
     * @return {@link WaitUntil} 实例
     */
    static <S> Before<S> waitUntil(Instant instant, S state) {
        return new WaitUntil<S>(instant, state);
    }

    /**
     * 创建业务拒绝前置决策。
     *
     * @param reason 拒绝原因，不能为 null
     * @param state  新策略状态，不能为 null
     * @param <S>    状态类型
     * @return {@link Reject} 实例
     */
    static <S> Before<S> reject(Reason reason, S state) {
        return new Reject<S>(reason, state);
    }

    /**
     * 创建故障拦截前置决策。
     *
     * @param failure 失败诊断，不能为 null
     * @param state   新策略状态，不能为 null
     * @param <S>     状态类型
     * @return {@link Fail} 实例
     */
    static <S> Before<S> fail(Failure failure, S state) {
        return new Fail<S>(failure, state);
    }

    /**
     * 创建正常返回后置决策。
     *
     * @param state 最终策略状态，不能为 null
     * @param <S>   状态类型
     * @return {@link Return} 实例
     */
    static <S> After<S> returning(S state) {
        return new Return<S>(state);
    }

    /**
     * 创建定时重试后置决策。
     *
     * @param instant 重试唤醒绝对时间，不能为 null
     * @param state   新策略状态，不能为 null
     * @param <S>     状态类型
     * @return {@link RetryAt} 实例
     */
    static <S> After<S> retryAt(Instant instant, S state) {
        return new RetryAt<S>(instant, state);
    }
}

