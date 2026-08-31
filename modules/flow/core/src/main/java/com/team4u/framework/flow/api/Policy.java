package com.team4u.framework.flow.api;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;

/**
 * 内存无状态治理策略核心接口 SPI。
 *
 * <p>用于在受保护的步骤/子流程执行前后执行前置准入控制（Gate）与后置完成观察（如限流、熔断、鉴权、指标埋点等）：
 * <ul>
 *   <li><b>前置准入（{@link #before}）</b>：在目标操作执行前调用，基于投影后的策略键 {@code key} 作出准入裁决（{@link Gate#proceed()} 放行、{@link Gate#reject(Reason)} 拒绝或 {@link Gate#fail(Failure)} 报错）；</li>
 *   <li><b>后置观察（{@link #after}）</b>：在目标操作执行完成后调用，接收不含载荷的完成摘要 {@link Completion} 用于更新外部统计或释放资源（默认空实现）。</li>
 * </ul>
 * </p>
 *
 * @param <K> 策略键类型（由节点输入通过 keyProjection 投影生成）
 * @author jay.wu
 */
public interface Policy<K> {

    /**
     * 前置准入决策：在目标步骤执行前调用。
     *
     * @param context 策略执行上下文（元数据、重试轮次 attempt、取消信号），保证非 null
     * @param key     从步骤输入数据投影得到的策略键，保证非 null
     * @return 门控决策（{@link Gate.Proceed}、{@link Gate.Reject} 或 {@link Gate.Fail}），不能返回 null
     * @throws Exception 当策略判定逻辑发生异常时抛出（将被框架捕获并转换为 Failed 结果）
     */
    Gate before(PolicyContext context, K key);

    /**
     * 后置完成观察：在目标步骤执行完成后调用。
     *
     * @param context    策略执行上下文，保证非 null
     * @param key        策略键，保证非 null
     * @param completion 步骤完成摘要（包含 Outcome.Kind 及关联的 Reason/Failure），保证非 null
     */
    default void after(PolicyContext context, K key, Completion completion) { }
}

