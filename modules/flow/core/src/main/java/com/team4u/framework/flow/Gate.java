package com.team4u.framework.flow;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 策略门控前置决策（Gate）：在 {@link Policy#before} 阶段对步骤执行准入判定的闭集代数模型。
 *
 * <p>包含三种互斥的裁决结果：
 * <ul>
 *   <li>{@link Proceed}：准予放行，允许受保护的目标操作继续执行；</li>
 *   <li>{@link Reject}：业务拒绝，携带 {@link Reason} 阻断执行并直接产生 Rejected 结果（触发降级/短路）；</li>
 *   <li>{@link Fail}：故障拦截，携带 {@link Failure} 阻断执行并直接产生 Failed 结果（可触发重试/恢复）。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public abstract class Gate {

    /**
     * 包级私有构造器，限制子类仅限本包内部。
     */
    Gate() { }

    /**
     * 创建准予放行决策（单例）。
     *
     * @return {@link Proceed} 决策实例
     */
    public static Gate proceed() {
        return Proceed.INSTANCE;
    }

    /**
     * 创建业务拒绝决策。
     *
     * @param reason 业务拒绝诊断信息，不能为 null
     * @return 携带该原因的 {@link Reject} 决策实例
     * @throws NullPointerException 当 {@code reason} 为 null 时抛出
     */
    public static Gate reject(Reason reason) {
        return new Reject(reason);
    }

    /**
     * 创建故障拦截决策。
     *
     * @param failure 故障诊断信息，不能为 null
     * @return 携带该信息的 {@link Fail} 决策实例
     * @throws NullPointerException 当 {@code failure} 为 null 时抛出
     */
    public static Gate fail(Failure failure) {
        return new Fail(failure);
    }

    /**
     * 放行决策：允许后续节点正常进入执行，无额外状态。
     */
    public static final class Proceed extends Gate {
        private static final Proceed INSTANCE = new Proceed();

        private Proceed() { }

        @Override
        public String toString() {
            return "Proceed";
        }
    }

    /**
     * 业务拒绝决策：拦截当前操作，携带业务拒绝诊断原因。
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    public static final class Reject extends Gate {
        /** 业务拒绝诊断原因。 */
        private final Reason reason;

        /**
         * 构造 Reject 决策。
         *
         * @param reason 拒绝原因，不能为 null
         */
        Reject(Reason reason) {
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /**
     * 故障拦截决策：拦截当前操作，携带故障失败诊断信息。
     */
    @Getter
    @Accessors(fluent = true)
    @EqualsAndHashCode(callSuper = false)
    @ToString
    public static final class Fail extends Gate {
        /** 失败故障诊断信息。 */
        private final Failure failure;

        /**
         * 构造 Fail 决策。
         *
         * @param failure 失败信息，不能为 null
         */
        Fail(Failure failure) {
            this.failure = Objects.requireNonNull(failure, "failure must not be null");
        }
    }
}

