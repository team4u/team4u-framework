package com.team4u.framework.flow.dsl;

import com.team4u.framework.flow.definition.binding.BoundFlow;
import com.team4u.framework.flow.definition.binding.FlowBinder;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.model.SourceSpan;
import com.team4u.framework.flow.definition.reader.FlowDefinitionReader;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.dsl.reader.TextFlowDefinitionReader;
import com.team4u.framework.flow.spi.OperationResolver;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 流程 DSL 解析与编译引擎实例（Flow DSL Engine）。
 *
 * <p>封装流程定义读取器 {@link FlowDefinitionReader}、符号注册表 {@link FlowDefinitionRegistry}
 * 与组件解析器 {@link OperationResolver}，支持定制前端解析、多流程拆解、类型检查与强类型绑定服务。</p>
 *
 * @author jay.wu
 */
public class FlowDslEngine {

    private static final FlowDslEngine DEFAULT_ENGINE = new FlowDslEngine(
            TextFlowDefinitionReader.INSTANCE,
            FlowDefinitionRegistry.empty(),
            null);

    private final FlowDefinitionReader reader;
    private final FlowDefinitionRegistry registry;
    private final OperationResolver resolver;

    public FlowDslEngine(
            FlowDefinitionReader reader,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        this.reader = reader != null ? reader : TextFlowDefinitionReader.INSTANCE;
        this.registry = registry != null ? registry : FlowDefinitionRegistry.empty();
        this.resolver = resolver;
    }

    /**
     * 获取默认配置的 FlowDslEngine 实例（使用 {@link TextFlowDefinitionReader} 与空注册表）。
     *
     * @return 默认 DSL 引擎实例
     */
    public static FlowDslEngine defaultEngine() {
        return DEFAULT_ENGINE;
    }

    /**
     * 创建 FlowDslEngine 构建器。
     *
     * @return 引擎构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 基于指定流程定义读取器创建引擎实例。
     *
     * @param reader 流程定义读取器
     * @return 引擎实例
     */
    public static FlowDslEngine withReader(FlowDefinitionReader reader) {
        return builder().reader(reader).build();
    }

    /**
     * 获取配置的流程定义读取器。
     *
     * @return 流程定义读取器
     */
    public FlowDefinitionReader reader() {
        return reader;
    }

    /**
     * 获取配置的预设符号注册表。
     *
     * @return 符号注册表
     */
    public FlowDefinitionRegistry registry() {
        return registry;
    }

    /**
     * 获取配置的预设组件解析器。
     *
     * @return 组件解析器
     */
    public OperationResolver resolver() {
        return resolver;
    }

    /**
     * 将源输入解析为外部配置模型 {@link FlowDefinition}（若有多个 flow 则返回最后一个/主流程）。
     *
     * @param source 源配置文本
     * @return 流程定义 AST
     */
    public FlowDefinition parse(String source) {
        return parse(source, null);
    }

    /**
     * 将源输入解析为带源码标识的外部配置模型 {@link FlowDefinition}。
     *
     * @param source     源配置文本
     * @param sourceName 源码文件名或资源标识
     * @return 流程定义 AST
     */
    public FlowDefinition parse(String source, String sourceName) {
        return reader.readDefinition(source, sourceName);
    }

    /**
     * 将源输入中的所有 flow 块解析为列表。
     *
     * @param source 源配置文本
     * @return 流程定义 AST 列表
     */
    public List<FlowDefinition> parseAll(String source) {
        return parseDefinitions(source, null);
    }

    /**
     * 将源输入中的所有 flow 块解析为列表。
     *
     * @param source     源配置文本
     * @param sourceName 源码文件名或资源标识
     * @return 流程定义 AST 列表
     */
    public List<FlowDefinition> parseAll(String source, String sourceName) {
        return parseDefinitions(source, sourceName);
    }

    /**
     * 将源输入中的所有 flow 块解析为列表（同 {@link #parseAll(String)}）。
     *
     * @param source 源配置文本
     * @return 流程定义 AST 列表
     */
    public List<FlowDefinition> parseDefinitions(String source) {
        return parseDefinitions(source, null);
    }

    /**
     * 将源输入中的所有 flow 块解析为列表（同 {@link #parseAll(String, String)}）。
     *
     * @param source     源配置文本
     * @param sourceName 源码文件名或资源标识
     * @return 流程定义 AST 列表
     */
    public List<FlowDefinition> parseDefinitions(String source, String sourceName) {
        List<FlowDefinition> definitions = reader.read(source, sourceName);
        return definitions != null ? definitions : Collections.<FlowDefinition>emptyList();
    }

    /**
     * 使用引擎预设的注册表与解析器将源输入解析并绑定为 {@link BoundFlow}。
     *
     * @param source 源配置文本
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(String source) {
        return bind(source, null, null, null, null);
    }

    /**
     * 使用引擎预设的注册表与解析器将源输入解析并绑定为 {@link BoundFlow}。
     *
     * @param source     源配置文本
     * @param sourceName 源码文件名或资源标识
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(String source, String sourceName) {
        return bind(source, sourceName, null, null, null);
    }

    /**
     * 将源输入解析并与指定的符号注册表绑定为 {@link BoundFlow}。
     *
     * @param source   源配置文本
     * @param registry 符号注册表
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(String source, FlowDefinitionRegistry registry) {
        return bind(source, null, null, registry, null);
    }

    /**
     * 将源输入解析并与指定的符号注册表及源码标识绑定为 {@link BoundFlow}。
     *
     * @param source     源配置文本
     * @param sourceName 源码文件名或资源标识
     * @param registry   符号注册表
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(String source, String sourceName, FlowDefinitionRegistry registry) {
        return bind(source, sourceName, null, registry, null);
    }

    /**
     * 将源输入解析并与指定的符号注册表及组件解析器绑定为 {@link BoundFlow}。
     *
     * @param source   源配置文本
     * @param registry 符号注册表
     * @param resolver 组件解析器（如 Spring Bean 解析器）
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(
            String source,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        return bind(source, null, null, registry, resolver);
    }

    /**
     * 将源输入解析并与指定的源码标识、符号注册表及组件解析器绑定为 {@link BoundFlow}。
     *
     * @param source     源配置文本
     * @param sourceName 源码文件名或资源标识
     * @param registry   符号注册表
     * @param resolver   组件解析器
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(
            String source,
            String sourceName,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        return bind(source, sourceName, null, registry, resolver);
    }

    /**
     * 将源输入解析并绑定为指定的目标流程。
     *
     * @param source       源配置文本
     * @param targetFlowId 目标主流程 ID
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bindTarget(String source, String targetFlowId) {
        return bind(source, null, targetFlowId, null, null);
    }

    /**
     * 将源输入解析并与指定的符号注册表绑定为指定的目标流程。
     *
     * @param source       源配置文本
     * @param targetFlowId 目标主流程 ID
     * @param registry     符号注册表
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bindTarget(String source, String targetFlowId, FlowDefinitionRegistry registry) {
        return bind(source, null, targetFlowId, registry, null);
    }

    /**
     * 将源输入解析并与指定的符号注册表及组件解析器绑定为指定的目标流程。
     *
     * @param source       源配置文本
     * @param targetFlowId 目标主流程 ID
     * @param registry     符号注册表
     * @param resolver     组件解析器
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bindTarget(
            String source,
            String targetFlowId,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        return bind(source, null, targetFlowId, registry, resolver);
    }

    /**
     * 将源输入解析并与指定的符号注册表绑定为指定的目标流程。
     *
     * @param source       源配置文本
     * @param sourceName   源码文件名或资源标识
     * @param targetFlowId 目标主流程 ID
     * @param registry     符号注册表
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(
            String source,
            String sourceName,
            String targetFlowId,
            FlowDefinitionRegistry registry) {
        return bind(source, sourceName, targetFlowId, registry, null);
    }

    /**
     * 全参数流程解析与绑定核心方法。
     *
     * @param source       源配置文本
     * @param sourceName   源码文件名或资源标识
     * @param targetFlowId 目标主流程 ID（若为 null 则默认采用最后一个 declared flow）
     * @param registry     符号注册表（若为 null 则回退为引擎预设注册表）
     * @param resolver     组件解析器（若为 null 则回退为引擎预设解析器）
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(
            String source,
            String sourceName,
            String targetFlowId,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        List<FlowDefinition> definitions = reader.read(source, sourceName);
        if (definitions == null || definitions.isEmpty()) {
            throw new FlowDiagnosticException(
                    DiagnosticCodes.INVALID_DEFINITION, "No flow definition found in source", SourceSpan.UNKNOWN);
        }

        FlowDefinitionRegistry effectiveRegistry = registry != null ? registry : this.registry;
        if (effectiveRegistry == null) {
            effectiveRegistry = FlowDefinitionRegistry.empty();
        }

        // 如果定义中包含多个 flow，或者已有 subflows，将所有解析出的 flow 注册到 multi-flow registry
        if (definitions.size() > 1 || !effectiveRegistry.subflows().isEmpty()) {
            FlowDefinitionRegistry.Builder regBuilder = FlowDefinitionRegistry.builder()
                    .subflows(effectiveRegistry.subflows())
                    .operations(effectiveRegistry.operations())
                    .policies(effectiveRegistry.policies())
                    .policyProviders(effectiveRegistry.policyProviders())
                    .projectors(effectiveRegistry.projectors())
                    .mergers(effectiveRegistry.mergers())
                    .keyProjections(effectiveRegistry.keyProjections())
                    .joins(effectiveRegistry.joins())
                    .resumePoints(effectiveRegistry.resumePoints())
                    .typeCodecs(effectiveRegistry.typeCodecs())
                    .fallbackResolver(effectiveRegistry.fallbackResolver());

            for (FlowDefinition def : definitions) {
                regBuilder.overrideSubflow(def);
            }
            effectiveRegistry = regBuilder.build();
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
                targetDef = effectiveRegistry.subflow(targetFlowId);
            }
            if (targetDef == null) {
                throw new FlowDiagnosticException(
                        DiagnosticCodes.UNKNOWN_FLOW, "Target flow not found: " + targetFlowId);
            }
        } else {
            targetDef = definitions.get(definitions.size() - 1);
        }

        OperationResolver effectiveResolver = resolver != null ? resolver : this.resolver;
        return FlowBinder.bind(targetDef, effectiveRegistry, effectiveResolver);
    }

    /**
     * 将已解析的 {@link FlowDefinition} 与预设符号注册表绑定为 {@link BoundFlow}。
     *
     * @param definition 流程定义 AST
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(FlowDefinition definition) {
        return bind(definition, this.registry, this.resolver);
    }

    /**
     * 将已解析的 {@link FlowDefinition} 与指定的符号注册表绑定为 {@link BoundFlow}。
     *
     * @param definition 流程定义 AST
     * @param registry   符号注册表
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(FlowDefinition definition, FlowDefinitionRegistry registry) {
        return bind(definition, registry, this.resolver);
    }

    /**
     * 将已解析的 {@link FlowDefinition} 与指定的符号注册表及组件解析器绑定为 {@link BoundFlow}。
     *
     * @param definition 流程定义 AST
     * @param registry   符号注册表
     * @param resolver   组件解析器
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(
            FlowDefinition definition,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        FlowDefinitionRegistry effectiveRegistry = registry != null ? registry : this.registry;
        OperationResolver effectiveResolver = resolver != null ? resolver : this.resolver;
        return FlowBinder.bind(definition, effectiveRegistry, effectiveResolver);
    }

    /**
     * FlowDslEngine 构建器。
     */
    public static final class Builder {
        private FlowDefinitionReader reader = TextFlowDefinitionReader.INSTANCE;
        private FlowDefinitionRegistry registry = FlowDefinitionRegistry.empty();
        private OperationResolver resolver;

        private Builder() { }

        /**
         * 设置流程定义读取器。
         *
         * @param reader 流程定义读取器
         * @return 当前构建器
         */
        public Builder reader(FlowDefinitionReader reader) {
            this.reader = reader != null ? reader : TextFlowDefinitionReader.INSTANCE;
            return this;
        }

        /**
         * 设置预设符号注册表。
         *
         * @param registry 符号注册表
         * @return 当前构建器
         */
        public Builder registry(FlowDefinitionRegistry registry) {
            this.registry = registry != null ? registry : FlowDefinitionRegistry.empty();
            return this;
        }

        /**
         * 设置预设组件解析器。
         *
         * @param resolver 组件解析器
         * @return 当前构建器
         */
        public Builder resolver(OperationResolver resolver) {
            this.resolver = resolver;
            return this;
        }

        /**
         * 构建不可变 FlowDslEngine 实例。
         *
         * @return 引擎实例
         */
        public FlowDslEngine build() {
            return new FlowDslEngine(reader, registry, resolver);
        }
    }
}
