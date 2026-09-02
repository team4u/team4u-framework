package com.team4u.framework.flow.definition.reader;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.model.SourceSpan;

import java.util.List;

/**
 * 流程定义读取器接口（Flow Definition Reader SPI）。
 *
 * <p>作为流程引擎前端读取与解析的统一抽象，负责将各种源格式（如文本 DSL、JSON、YAML、数据库配置等）
 * 解析为中立的外部流程定义模型 {@link FlowDefinition}。</p>
 *
 * @author jay.wu
 */
@FunctionalInterface
public interface FlowDefinitionReader {

    /**
     * 将源输入读取解析为流程定义列表。
     *
     * @param source     源配置文本或内容
     * @param sourceName 源码文件名或资源标识（可为 null）
     * @return 流程定义 AST 列表
     * @throws FlowDiagnosticException 当解析语法或结构不合法时抛出
     */
    List<FlowDefinition> read(String source, String sourceName);

    /**
     * 将源输入读取解析为流程定义列表。
     *
     * @param source 源配置文本或内容
     * @return 流程定义 AST 列表
     * @throws FlowDiagnosticException 当解析语法或结构不合法时抛出
     */
    default List<FlowDefinition> read(String source) {
        return read(source, null);
    }

    /**
     * 将源输入读取解析为单个流程定义（若包含多个 flow 则返回最后一个/主流程）。
     *
     * @param source     源配置文本或内容
     * @param sourceName 源码文件名或资源标识（可为 null）
     * @return 流程定义 AST
     * @throws FlowDiagnosticException 当解析语法或结构不合法，或未找到流程定义时抛出
     */
    default FlowDefinition readDefinition(String source, String sourceName) {
        List<FlowDefinition> definitions = read(source, sourceName);
        if (definitions == null || definitions.isEmpty()) {
            throw new FlowDiagnosticException(new Diagnostic(
                    DiagnosticCodes.INVALID_DEFINITION,
                    "No flow definition found in source",
                    SourceSpan.UNKNOWN));
        }
        return definitions.get(definitions.size() - 1);
    }

    /**
     * 将源输入读取解析为单个流程定义（若包含多个 flow 则返回最后一个/主流程）。
     *
     * @param source 源配置文本或内容
     * @return 流程定义 AST
     * @throws FlowDiagnosticException 当解析语法或结构不合法，或未找到流程定义时抛出
     */
    default FlowDefinition readDefinition(String source) {
        return readDefinition(source, null);
    }
}
