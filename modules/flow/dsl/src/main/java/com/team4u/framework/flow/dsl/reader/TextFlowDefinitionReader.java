package com.team4u.framework.flow.dsl.reader;

import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.reader.FlowDefinitionReader;
import com.team4u.framework.flow.dsl.lexer.FlowLexer;
import com.team4u.framework.flow.dsl.parser.FlowDslParser;

import java.util.List;
import java.util.Objects;

/**
 * 文本 DSL 流程定义读取器（Text Flow Definition Reader）。
 *
 * <p>基于 {@link FlowLexer} 与 {@link FlowDslParser} 将 Flow 文本 DSL 解析为 {@link FlowDefinition} AST 列表。</p>
 *
 * @author jay.wu
 */
public final class TextFlowDefinitionReader implements FlowDefinitionReader {

    /**
     * 默认共享单例实例。
     */
    public static final TextFlowDefinitionReader INSTANCE = new TextFlowDefinitionReader();

    @Override
    public List<FlowDefinition> read(String source, String sourceName) {
        Objects.requireNonNull(source, "source must not be null");
        FlowLexer lexer = new FlowLexer(source, sourceName);
        FlowDslParser parser = new FlowDslParser(lexer.tokenize(), sourceName);
        return parser.parseDefinitions();
    }
}
