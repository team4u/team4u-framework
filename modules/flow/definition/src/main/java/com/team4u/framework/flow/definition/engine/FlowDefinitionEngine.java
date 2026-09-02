package com.team4u.framework.flow.definition.engine;

import com.team4u.framework.flow.definition.binding.BoundFlow;
import com.team4u.framework.flow.definition.binding.FlowBinder;
import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.reader.FlowDefinitionReader;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.spi.OperationResolver;
import com.team4u.framework.parser.SourceSpan;

import java.util.*;

/**
 * 流程定义解析与绑定引擎（Flow Definition Engine）。
 *
 * <p>作为统一的前端中立执行引擎，负责调用 {@link FlowDefinitionReader} 获取 {@link FlowDefinition} 集合、
 * 执行显式目标主流程选择、注册 subflow 并通过 {@link FlowBinder} 完成类型检查与强类型绑定。</p>
 *
 * @author jay.wu
 */
public final class FlowDefinitionEngine {

    private final FlowDefinitionReader reader;
    private final FlowDefinitionRegistry registry;
    private final OperationResolver resolver;

    public FlowDefinitionEngine(
            FlowDefinitionReader reader,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        this.reader = Objects.requireNonNull(reader, "FlowDefinitionReader must not be null");
        this.registry = registry != null ? registry : FlowDefinitionRegistry.empty();
        this.resolver = resolver;
    }

    /**
     * 创建 FlowDefinitionEngine 构建器。
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
    public static FlowDefinitionEngine withReader(FlowDefinitionReader reader) {
        return builder().reader(reader).build();
    }

    /**
     * 获取配置的流程定义读取器。
     */
    public FlowDefinitionReader reader() {
        return reader;
    }

    /**
     * 获取配置的预设符号注册表。
     */
    public FlowDefinitionRegistry registry() {
        return registry;
    }

    /**
     * 获取配置的预设组件解析器。
     */
    public OperationResolver resolver() {
        return resolver;
    }

    /**
     * 读取源输入中的所有流程定义。
     *
     * @param source 源配置文本或内容
     * @return 流程定义列表
     */
    public List<FlowDefinition> readAll(String source) {
        return readAll(source, null);
    }

    /**
     * 读取源输入中的所有流程定义。
     *
     * @param source     源配置文本或内容
     * @param sourceName 源码标识或资源路径
     * @return 流程定义列表
     */
    public List<FlowDefinition> readAll(String source, String sourceName) {
        List<FlowDefinition> definitions = reader.read(source, sourceName);
        Objects.requireNonNull(definitions, "FlowDefinitionReader must not return null");
        List<FlowDefinition> snapshot = new ArrayList<FlowDefinition>(definitions.size());
        for (FlowDefinition def : definitions) {
            snapshot.add(Objects.requireNonNull(def, "FlowDefinition element must not be null"));
        }
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * 使用引擎预设的注册表与解析器解析并绑定源输入。
     *
     * @param source 源配置文本或内容
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(String source) {
        return bind(source, null, null, null, null);
    }

    /**
     * 使用引擎预设的注册表与解析器解析并绑定带源码标识的源输入。
     *
     * @param source     源配置文本或内容
     * @param sourceName 源码标识或资源路径
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(String source, String sourceName) {
        return bind(source, sourceName, null, null, null);
    }

    /**
     * 使用指定的符号注册表解析并绑定源输入。
     *
     * @param source   源配置文本或内容
     * @param registry 符号注册表
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(String source, FlowDefinitionRegistry registry) {
        return bind(source, null, null, registry, null);
    }

    /**
     * 使用指定的源码标识与符号注册表解析并绑定源输入。
     *
     * @param source     源配置文本或内容
     * @param sourceName 源码标识或资源路径
     * @param registry   符号注册表
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(String source, String sourceName, FlowDefinitionRegistry registry) {
        return bind(source, sourceName, null, registry, null);
    }

    /**
     * 使用指定的符号注册表及组件解析器解析并绑定源输入。
     *
     * @param source   源配置文本或内容
     * @param registry 符号注册表
     * @param resolver 组件解析器
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bind(
            String source,
            FlowDefinitionRegistry registry,
            OperationResolver resolver) {
        return bind(source, null, null, registry, resolver);
    }

    /**
     * 使用指定的源码标识、符号注册表及组件解析器解析并绑定源输入。
     *
     * @param source     源配置文本或内容
     * @param sourceName 源码标识或资源路径
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
     * 解析源输入并绑定为指定的目标主流程。
     *
     * @param source       源配置文本或内容
     * @param targetFlowId 目标主流程 ID
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bindTarget(String source, String targetFlowId) {
        return bind(source, null, targetFlowId, null, null);
    }

    /**
     * 解析带源码标识的源输入并绑定为指定的目标主流程。
     *
     * @param source       源配置文本或内容
     * @param sourceName   源码标识或资源路径
     * @param targetFlowId 目标主流程 ID
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bindTarget(String source, String sourceName, String targetFlowId) {
        return bind(source, sourceName, targetFlowId, null, null);
    }

    /**
     * 解析源输入并与指定符号注册表绑定为目标主流程。
     *
     * @param source       源配置文本或内容
     * @param targetFlowId 目标主流程 ID
     * @param registry     符号注册表
     * @return 绑定后的 BoundFlow
     */
    public BoundFlow bindTarget(String source, String targetFlowId, FlowDefinitionRegistry registry) {
        return bind(source, null, targetFlowId, registry, null);
    }

    /**
     * 解析源输入并与指定符号注册表及组件解析器绑定为目标主流程。
     *
     * @param source       源配置文本或内容
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
     * 全参数流程解析与绑定核心方法。
     *
     * @param source       源配置文本或内容
     * @param sourceName   源码标识或资源路径
     * @param targetFlowId 目标主流程 ID（若为 null 且仅有单个 flow 则自动采用；若有多个 flow 则必须显式指定）
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
        List<FlowDefinition> definitions = readAll(source, sourceName);

        if (definitions.isEmpty()) {
            throw new FlowDiagnosticException(new Diagnostic(
                    DiagnosticCodes.INVALID_DEFINITION, "No flow definition found in source", SourceSpan.UNKNOWN));
        }

        FlowDefinitionRegistry baseRegistry = registry != null ? registry : this.registry;
        if (baseRegistry == null) {
            baseRegistry = FlowDefinitionRegistry.empty();
        }

        // 检查批次内重复 flow id 以及与已有 registry 的命名冲突（禁止隐式覆盖）
        Set<String> seenIds = new HashSet<String>();
        for (FlowDefinition def : definitions) {
            if (!seenIds.add(def.id())) {
                throw new FlowDiagnosticException(new Diagnostic(
                        DiagnosticCodes.DUPLICATE_FLOW_ID, "Duplicate flow id: " + def.id(), def.span()));
            }
            if (baseRegistry.subflow(def.id()) != null) {
                throw new FlowDiagnosticException(new Diagnostic(
                        DiagnosticCodes.DUPLICATE_FLOW_ID, "Flow id already registered: " + def.id(), def.span()));
            }
        }

        // 无条件将本次 definitions 注册至 effectiveRegistry，确保单/多 flow 行为一致且支持自环与相互调用检测
        FlowDefinitionRegistry.Builder regBuilder = baseRegistry.toBuilder();
        for (FlowDefinition def : definitions) {
            regBuilder.subflow(def);
        }
        FlowDefinitionRegistry effectiveRegistry = regBuilder.build();

        // 目标流程选择（严格限定于本次 source definitions 集合中，不跨界 fallback 外部已注册流程）
        FlowDefinition targetDef = null;
        if (targetFlowId == null) {
            if (definitions.size() == 1) {
                targetDef = definitions.get(0);
            } else {
                throw new FlowDiagnosticException(new Diagnostic(
                        DiagnosticCodes.AMBIGUOUS_TARGET_FLOW,
                        "Multiple flow definitions found; targetFlowId must be specified",
                        SourceSpan.UNKNOWN));
            }
        } else {
            for (FlowDefinition def : definitions) {
                if (targetFlowId.equals(def.id())) {
                    targetDef = def;
                    break;
                }
            }
            if (targetDef == null) {
                throw new FlowDiagnosticException(new Diagnostic(
                        DiagnosticCodes.UNKNOWN_FLOW,
                        "Target flow not found in source: " + targetFlowId,
                        SourceSpan.UNKNOWN));
            }
        }

        OperationResolver effectiveResolver = resolver != null ? resolver : this.resolver;
        return FlowBinder.bind(targetDef, effectiveRegistry, effectiveResolver);
    }

    /**
     * FlowDefinitionEngine 构建器。
     */
    public static final class Builder {
        private FlowDefinitionReader reader;
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
            this.reader = reader;
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
         * 构建不可变 FlowDefinitionEngine 实例。
         *
         * @return 引擎实例
         */
        public FlowDefinitionEngine build() {
            return new FlowDefinitionEngine(reader, registry, resolver);
        }
    }
}
