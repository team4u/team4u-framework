package com.team4u.framework.flow.spi;
import com.team4u.framework.flow.model.Outcome;

/**
 * 降级/回退（Fallback）控制节点的触发条件枚举。
 *
 * <p>决定了前序分支产生何种非成功状态时触发向后序分支的转移降级：
 * <ul>
 *   <li>{@link #SKIPPED}：当主分支返回 {@link Outcome.Skipped}（弃权/无匹配）时触发，用于 {@code firstApplicable} 等多分支尝试；</li>
 *   <li>{@link #FAILED}：当主分支返回 {@link Outcome.Failed}（失败/异常）时触发，用于 {@code recoverWith} 等故障恢复。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public enum FallbackTrigger {
    /** 弃权触发：当前序步骤产生 Skipped 结果时尝试下一分支。 */
    SKIPPED,
    /** 失败触发：当前序步骤产生 Failed 结果时尝试下一恢复分支。 */
    FAILED
}

