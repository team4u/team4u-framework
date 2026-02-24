package com.team4u.criterion.parser;

import com.team4u.criterion.model.Criterion;
import com.team4u.policy.ContextPolicy;

/**
 * 语法处理器接口
 * <p>
 * 基于插件责任链模式和策略类优先级的支持，允许在不修改 StandardCriterionParser 源码的情况下扩展新语法。
 */
public interface SyntaxHandler extends ContextPolicy<CriterionParser.Context> {

    @Override
    default boolean supports(CriterionParser.Context context) {
        return true;
    }

    /**
     * 默认优先级
     * <p>
     * 设置一个默认的中等偏后优先级（例如 0），确保：
     * 1. 业务自定义 Handler 可以通过返回负数获得更高优先级。
     * 2.
     * {@link com.team4u.criterion.parser.handler.RelationalOperatorSyntaxHandler}
     * 可以进行兜底。
     */
    @Override
    default int priority() {
        // 默认优先级，允许自定义 Handler （如 -100）排在前面，内置的排在后面
        return 0;
    }

    /**
     * 尝试解析当前语法
     * <p>
     * Handler 应该首先检查当前 Token 是否匹配其处理的语法，
     * 如果不匹配则返回 null，让下一个 Handler 处理。
     *
     * @param subject 当前的主语（变量名），例如 "age > 18" 中的 "age"
     * @param context 解析上下文，提供 Token 流操作
     * @return 如果成功处理，返回对应的 Criterion；如果不识别当前语法，返回 null
     */
    Criterion tryParse(String subject, CriterionParser.Context context);
}