package com.team4u.framework.singleflight.policy;

import com.team4u.framework.policy.api.KeyedPolicy;

/**
 * 业务 key 摘要策略：把渲染后的业务 key 变换为可直接进入存储的摘要值。
 * <p>
 * 摘要由规则显式声明（{@code keyDigest} 字段按名引用），用于敏感 key
 * （手机号、证件号等）不明文落入存储；需要抗穷举能力（低熵标识符防彩虹表）时，
 * 自行实现 HMAC 等算法并注册，规则按名引用。
 * </p>
 * <p>
 * 实现约束：
 * </p>
 * <ul>
 *     <li>{@link #digest(String)} 必须是纯函数——同一输入永远得到同一输出，
 *     否则同 key 的并发调用会散落到不同执行窗口，合并失效；</li>
 *     <li>返回值必须稳定且可作为存储 key 的一部分（空串、null 均视为非法，
 *     由引擎在组装期拒绝）；</li>
 *     <li>摘要发生在 percent 编码之前，实现无需关心存储层 key 约束。</li>
 * </ul>
 *
 * @author jay.wu
 */
public interface SingleFlightKeyDigest extends KeyedPolicy<String> {

    /**
     * 对渲染后的业务 key 做摘要。
     *
     * @param renderedKey 规则 key 模板渲染后的业务 key
     * @return 稳定的摘要值（作为最终存储 key 的一部分）
     */
    String digest(String renderedKey);
}
