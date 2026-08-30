package com.team4u.framework.router.api.trace;

import lombok.Data;

/**
 * 单条规则的执行轨迹
 */
@Data
public class RuleTrace {

    /**
     * 评估的条件 (表达式或 Key)
     */
    private String condition;

    /**
     * 是否评估通过
     */
    private boolean matched;

    /**
     * 附加的底层诊断信息 (如表达式计算树的可视化输出)
     */
    private Object diagnosticDetail;

    /**
     * 是否是兜底逻辑
     */
    private boolean isFallback;

    /**
     * 创建普通规则轨迹
     *
     * @param condition 评估条件
     * @param matched   是否匹配
     * @param detail    详细诊断信息
     * @return 规则轨迹
     */
    public static RuleTrace normal(String condition, boolean matched, Object detail) {
        RuleTrace trace = new RuleTrace();
        trace.setCondition(condition);
        trace.setMatched(matched);
        trace.setDiagnosticDetail(detail);
        return trace;
    }

    /**
     * 创建兜底逻辑轨迹
     *
     * @param matched 是否走入兜底逻辑
     * @return 规则轨迹
     */
    public static RuleTrace fallback(boolean matched) {
        RuleTrace trace = new RuleTrace();
        trace.setFallback(true);
        trace.setMatched(matched);
        trace.setCondition("FALLBACK");
        return trace;
    }
}
