package com.team4u.framework.flow.spi;
import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.Retry;

/**
 * 流程控制增强节点（Control Node）的类型分类枚举。
 *
 * <p>表示环绕业务步骤的外围治理控制类型：
 * <ul>
 *   <li>{@link #POLICY}：内存无状态策略控制（{@link Policy}），包含准入前置决策（Gate）与后置完成观察；</li>
 *   <li>{@link #PERSISTENT_POLICY}：有状态持久化策略控制（{@link PersistentPolicy}），支持状态变迁与定时唤醒重试；</li>
 *   <li>{@link #RETRY}：固定重试策略控制（{@link Retry}），指定最大尝试次数与退避时长；</li>
 *   <li>{@link #TIMEOUT}：超时控制，指定作用域执行的绝对截止时限。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public enum ControlKind {
    /** 内存无状态策略拦截。 */
    POLICY,
    /** 持久化有状态策略拦截。 */
    PERSISTENT_POLICY,
    /** 失败重试控制。 */
    RETRY,
    /** 作用域超时控制。 */
    TIMEOUT
}

