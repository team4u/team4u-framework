package com.team4u.framework.flow.dsl;

import com.team4u.framework.flow.definition.binding.BoundFlow;
import com.team4u.framework.flow.definition.binding.FlowBinder;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.dsl.parser.FlowDslParser;
import com.team4u.framework.flow.spi.OperationResolver;

import java.util.List;
import java.util.Objects;

/**
 * 流程文本 DSL 核心统一门面入口（Flow DSL Facade）。
 *
 * <p>提供文本 DSL 语法解析、多流程拆解、类型校验、符号解析绑定与 Local/Durable 可执行流编译的一站式服务。</p>
 *
 * @author jay.wu
 */
public final class FlowDsl {

    private FlowDsl() { }

    /**
     * 将文本 DSL 解析为外部配置模型 {@link FlowDefinition}（若有多个 flow 则返回最后一个/主流程）。
     *
     * @param dsl DSL 文本内容
     * @return 流程定义 AST
     */
    public static FlowDefinition parse(String dsl) {
        return FlowDslParser.parse(dsl);
    }

    /**
     * 将文本 DSL 解析为带源码标识的外部配置模型 {@link FlowDefinition}。
     *
     * @param dsl        DSL 文本内容
     * @param sourceName 源码文件名或资源标识
     * @return 流程定义 AST
     */
    public static FlowDefinition parse(String dsl, String sourceName) {
        return FlowDslParser.parse(dsl, sourceName);
    }

    /**
     * 将文本 DSL 中的所有 flow 块解析为列表。
     *
     * @param dsl DSL 文本内容
     * @return 流程定义 AST 列表
     */
    public static List<FlowDefinition> parseAll(String dsl) {
        return FlowDslParser.parseDefinitions(dsl);
    }

    /**
     * 将文本 DSL 中的所有 flow 块解析为列表。
     *
     * @param dsl        DSL 文本内容
     * @param sourceName 源码文件名或资源标识
     * @return 流程定义 AST 列表
     */
    public static List<FlowDefinition> parseAll(String dsl, String sourceName) {
        return FlowDslParser.parseDefinitions(dsl, sourceName);
    }

    /**
     * 将文本 DSL 中的所有 flow 块解析为列表（同 {@link #parseAll(String)}）。
     *
     * @param dsl DSL 文本内容
     * @return 流程定义 AST 列表
     */
    public static List<FlowDefinition> parseDefinitions(String dsl) {
        return FlowDslParser.parseDefinitions(dsl);
    }

    /**
     * 将文本 DSL 中的所有 flow 块解析为列表（同 {@link #parseAll(String, String)}）。
     *
     * @param dsl        DSL 文本内容
     * @param sourceName 源码文件名或资源标识
     * @return 流程定义 AST 列表
     */
    public static List<FlowDefinition> parseDefinitions(String dsl, String sourceName) {
        return FlowDslParser.parseDefinitions(dsl, sourceName);
    }

    /**
     * 将文本 DSL 解析并与符号注册表绑定为 {@link BoundFlow}。
     *
     * @param dsl      DSL 文本内容
     * @param registry 符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(String dsl, FlowDefinitionRegistry registry) {
        return bind(dsl, null, null, registry, null);
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
        return bind(dsl, null, targetFlowId, registry, null);
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
        return bind(dsl, null, targetFlowId, registry, resolver);
    }

    /**
     * 将文本 DSL 解析并与符号注册表绑定为 {@link BoundFlow}。
     *
     * @param dsl        DSL 文本内容
     * @param sourceName 源码文件名或资源标识
     * @param registry   符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(String dsl, String sourceName, FlowDefinitionRegistry registry) {
        return bind(dsl, sourceName, null, registry, null);
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
        return bind(dsl, null, null, registry, resolver);
    }

    /**
     * 将文本 DSL 解析并与符号注册表及组件解析器绑定为 {@link BoundFlow}。
     *
     * @param dsl        DSL 文本内容
     * @param sourceName 源码文件名或资源标识
     * @param registry   符号注册表
     * @param resolver   组件解析器
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(
            String dsl,
            String sourceName,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        return bind(dsl, sourceName, null, registry, resolver);
    }

    /**
     * 将文本 DSL 解析并与符号注册表绑定为指定的目标流程。
     *
     * @param dsl          DSL 文本内容
     * @param sourceName   源码文件名或资源标识
     * @param targetFlowId 目标主流程 ID
     * @param registry     符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(
            String dsl,
            String sourceName,
            String targetFlowId,
            FlowDefinitionRegistry registry) {
        return bind(dsl, sourceName, targetFlowId, registry, null);
    }

    /**
     * 将文本 DSL 解析并与符号注册表及组件解析器绑定为指定的目标流程。
     *
     * @param dsl          DSL 文本内容
     * @param sourceName   源码文件名或资源标识
     * @param targetFlowId 目标主流程 ID（若为 null 则默认采用最后一个 declared flow）
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
        List<FlowDefinition> definitions = FlowDslParser.parseDefinitions(dsl, sourceName);
        if (registry == null) {
            registry = FlowDefinitionRegistry.empty();
        }

        // 如果定义中包含多个 flow，或者已有 subflows，将所有解析出的 flow 注册到 multi-flow registry
        if (definitions.size() > 1 || !registry.subflows().isEmpty()) {
            FlowDefinitionRegistry.Builder builder = FlowDefinitionRegistry.builder()
                    .subflows(registry.subflows())
                    .operations(registry.operations())
                    .policies(registry.policies())
                    .policyProviders(registry.policyProviders())
                    .projectors(registry.projectors())
                    .mergers(registry.mergers())
                    .keyProjections(registry.keyProjections())
                    .joins(registry.joins())
                    .resumePoints(registry.resumePoints())
                    .typeCodecs(registry.typeCodecs())
                    .fallbackResolver(registry.fallbackResolver());

            for (FlowDefinition def : definitions) {
                builder.overrideSubflow(def);
            }
            registry = builder.build();
        }

        FlowDefinition targetDef = null;
        if (targetFlowId != null) {
            for (FlowDefinition def : definitions) {
                if (targetFlowId.equals(def.id())) {
                    targetDef = def;
                    break;
                }
            }
            if (targetDef == null) {
                targetDef = registry.subflow(targetFlowId);
            }
            if (targetDef == null) {
                throw new FlowDiagnosticException(
                        DiagnosticCodes.UNKNOWN_FLOW, "Target flow not found: " + targetFlowId);
            }
        } else {
            targetDef = definitions.get(definitions.size() - 1);
        }

        return FlowBinder.bind(targetDef, registry, resolver);
    }

    /**
     * 将已解析的 {@link FlowDefinition} 与符号注册表绑定为 {@link BoundFlow}。
     *
     * @param definition 流程定义 AST
     * @param registry   符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(FlowDefinition definition, FlowDefinitionRegistry registry) {
        return FlowBinder.bind(definition, registry, (OperationResolver) null);
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
        return FlowBinder.bind(definition, registry, resolver);
    }
}
