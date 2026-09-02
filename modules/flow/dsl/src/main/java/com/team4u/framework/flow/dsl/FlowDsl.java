package com.team4u.framework.flow.dsl;

import com.team4u.framework.flow.definition.binding.BoundFlow;
import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.engine.FlowDefinitionEngine;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.dsl.reader.TextFlowDefinitionReader;
import com.team4u.framework.flow.spi.OperationResolver;
import com.team4u.framework.parser.SourceSpan;

import java.util.List;

/**
 * 流程文本 DSL 核心门面入口（Flow DSL Facade）。
 *
 * <p>提供文本 DSL 语法解析、多流程拆解与 Local/Durable 可执行流绑定的静态门面服务。</p>
 *
 * @author jay.wu
 */
public final class FlowDsl {

    private static final FlowDefinitionEngine DEFAULT_ENGINE =
            FlowDefinitionEngine.withReader(TextFlowDefinitionReader.INSTANCE);

    private FlowDsl() { }

    /**
     * 将文本 DSL 解析为单个流程定义 AST。
     *
     * @param dsl DSL 文本内容
     * @return 流程定义 AST
     * @throws FlowDiagnosticException 当语法错误、无定义或定义多于 1 个时抛出
     */
    public static FlowDefinition parse(String dsl) {
        return parse(dsl, null);
    }

    /**
     * 将文本 DSL 解析为带源码标识的单个流程定义 AST。
     *
     * @param dsl        DSL 文本内容
     * @param sourceName 源码标识或资源路径
     * @return 流程定义 AST
     * @throws FlowDiagnosticException 当语法错误、无定义或定义多于 1 个时抛出
     */
    public static FlowDefinition parse(String dsl, String sourceName) {
        List<FlowDefinition> defs = parseAll(dsl, sourceName);
        if (defs.isEmpty()) {
            throw new FlowDiagnosticException(new Diagnostic(
                    DiagnosticCodes.INVALID_DEFINITION,
                    "No flow definition found in source",
                    SourceSpan.UNKNOWN));
        }
        if (defs.size() > 1) {
            throw new FlowDiagnosticException(new Diagnostic(
                    DiagnosticCodes.AMBIGUOUS_TARGET_FLOW,
                    "Multiple flow definitions found; targetFlowId must be specified",
                    SourceSpan.UNKNOWN));
        }
        return defs.get(0);
    }

    /**
     * 将文本 DSL 中的所有 flow 块解析为列表。
     *
     * @param dsl DSL 文本内容
     * @return 流程定义 AST 列表
     */
    public static List<FlowDefinition> parseAll(String dsl) {
        return parseAll(dsl, null);
    }

    /**
     * 将文本 DSL 中的所有 flow 块解析为列表。
     *
     * @param dsl        DSL 文本内容
     * @param sourceName 源码标识或资源路径
     * @return 流程定义 AST 列表
     */
    public static List<FlowDefinition> parseAll(String dsl, String sourceName) {
        return DEFAULT_ENGINE.readAll(dsl, sourceName);
    }

    /**
     * 将文本 DSL 解析并与符号注册表绑定为 {@link BoundFlow}。
     *
     * @param dsl      DSL 文本内容
     * @param registry 符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(String dsl, FlowDefinitionRegistry registry) {
        return DEFAULT_ENGINE.bind(dsl, registry);
    }

    /**
     * 将文本 DSL 解析并与源码标识及符号注册表绑定为 {@link BoundFlow}。
     *
     * @param dsl        DSL 文本内容
     * @param sourceName 源码标识或资源路径
     * @param registry   符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(String dsl, String sourceName, FlowDefinitionRegistry registry) {
        return DEFAULT_ENGINE.bind(dsl, sourceName, registry);
    }

    /**
     * 将文本 DSL 解析并与符号注册表及组件解析器绑定为 {@link BoundFlow}。
     *
     * @param dsl      DSL 文本内容
     * @param registry 符号注册表
     * @param resolver 组件解析器（如 Spring Bean 解析器）
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(
            String dsl,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        return DEFAULT_ENGINE.bind(dsl, registry, resolver);
    }

    /**
     * 将文本 DSL 解析并与源码标识、符号注册表及组件解析器绑定为 {@link BoundFlow}。
     *
     * @param dsl        DSL 文本内容
     * @param sourceName 源码标识或资源路径
     * @param registry   符号注册表
     * @param resolver   组件解析器
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(
            String dsl,
            String sourceName,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        return DEFAULT_ENGINE.bind(dsl, sourceName, registry, resolver);
    }

    /**
     * 将文本 DSL 解析并与符号注册表绑定为指定的目标流程。
     *
     * @param dsl          DSL 文本内容
     * @param targetFlowId 目标主流程 ID
     * @param registry     符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bindTarget(String dsl, String targetFlowId, FlowDefinitionRegistry registry) {
        return DEFAULT_ENGINE.bindTarget(dsl, targetFlowId, registry);
    }

    /**
     * 将文本 DSL 解析并与符号注册表及组件解析器绑定为指定的目标流程。
     *
     * @param dsl          DSL 文本内容
     * @param targetFlowId 目标主流程 ID
     * @param registry     符号注册表
     * @param resolver     组件解析器
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bindTarget(
            String dsl,
            String targetFlowId,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        return DEFAULT_ENGINE.bindTarget(dsl, targetFlowId, registry, resolver);
    }

    /**
     * 将文本 DSL 解析并与源码标识及符号注册表绑定为指定的目标流程。
     *
     * @param dsl          DSL 文本内容
     * @param sourceName   源码标识或资源路径
     * @param targetFlowId 目标主流程 ID
     * @param registry     符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(
            String dsl,
            String sourceName,
            String targetFlowId,
            FlowDefinitionRegistry registry) {
        return DEFAULT_ENGINE.bind(dsl, sourceName, targetFlowId, registry, null);
    }

    /**
     * 将文本 DSL 解析并与源码标识、符号注册表及组件解析器绑定为指定的目标流程。
     *
     * @param dsl          DSL 文本内容
     * @param sourceName   源码标识或资源路径
     * @param targetFlowId 目标主流程 ID
     * @param registry     符号注册表
     * @param resolver     组件解析器
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(
            String dsl,
            String sourceName,
            String targetFlowId,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        return DEFAULT_ENGINE.bind(dsl, sourceName, targetFlowId, registry, resolver);
    }

    /**
     * 将已解析的 {@link FlowDefinition} 与符号注册表绑定为 {@link BoundFlow}。
     *
     * @param definition 流程定义 AST
     * @param registry   符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(FlowDefinition definition, FlowDefinitionRegistry registry) {
        return DEFAULT_ENGINE.bind(definition, registry);
    }

    /**
     * 将已解析的 {@link FlowDefinition} 与符号注册表及组件解析器绑定为 {@link BoundFlow}。
     *
     * @param definition 流程定义 AST
     * @param registry   符号注册表
     * @param resolver   组件解析器
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(
            FlowDefinition definition,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        return DEFAULT_ENGINE.bind(definition, registry, resolver);
    }
}
