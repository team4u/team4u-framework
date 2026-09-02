package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;

import java.io.Serializable;

/**
 * 外部流程配置规范抽象语法树（FlowSpec AST）公共接口。
 *
 * <p>FlowSpec 是纯数据模型，不包含任何 Java Class、Function、Operation 实例或运行时执行状态，
 * 适用于文本 DSL、JSON、YAML 与可视化 UI 配置编排。</p>
 *
 * @author jay.wu
 */
public interface FlowSpec extends Serializable {

    /**
     * 获取当前节点对应的源码文本位置区间。
     *
     * @return 源码位置区间
     */
    SourceSpan span();
}
