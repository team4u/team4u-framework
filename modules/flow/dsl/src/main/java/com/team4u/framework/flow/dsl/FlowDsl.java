package com.team4u.framework.flow.dsl;

import com.team4u.framework.flow.definition.binding.BoundFlow;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.reader.FlowDefinitionReader;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.spi.OperationResolver;

import java.util.List;

/**
 * 流程 DSL 核心统一门面入口（Flow DSL Facade）。
 *
 * <p>提供文本 DSL 语法解析、多流程拆解、类型校验、符号解析绑定与 Local/Durable 可执行流编译的一站式静态门面服务。
 * 内部委托给 {@link FlowDslEngine} 默认实例执行。</p>
 *
 * @author jay.wu
 */
public final class FlowDsl {

    private FlowDsl() { }

    /**
     * 获取默认配置的 {@link FlowDslEngine} 实例。
     *
     * @return 默认 DSL 引擎实例
     */
    public static FlowDslEngine engine() {
        return FlowDslEngine.defaultEngine();
    }

    /**
     * 创建 {@link FlowDslEngine} 构建器。
     *
     * @return 引擎构建器实例
     */
    public static FlowDslEngine.Builder builder() {
        return FlowDslEngine.builder();
    }

    /**
     * 基于指定流程定义读取器创建 {@link FlowDslEngine} 实例。
     *
     * @param reader 流程定义读取器
     * @return 引擎实例
     */
    public static FlowDslEngine withReader(FlowDefinitionReader reader) {
        return FlowDslEngine.withReader(reader);
    }

    /**
     * 将文本 DSL 解析为外部配置模型 {@link FlowDefinition}（若有多个 flow 则返回最后一个/主流程）。
     *
     * @param dsl DSL 文本内容
     * @return 流程定义 AST
     */
    public static FlowDefinition parse(String dsl) {
        return FlowDslEngine.defaultEngine().parse(dsl);
    }

    /**
     * 将文本 DSL 解析为带源码标识的外部配置模型 {@link FlowDefinition}。
     *
     * @param dsl        DSL 文本内容
     * @param sourceName 源码文件名或资源标识
     * @return 流程定义 AST
     */
    public static FlowDefinition parse(String dsl, String sourceName) {
        return FlowDslEngine.defaultEngine().parse(dsl, sourceName);
    }

    /**
     * 将文本 DSL 中的所有 flow 块解析为列表。
     *
     * @param dsl DSL 文本内容
     * @return 流程定义 AST 列表
     */
    public static List<FlowDefinition> parseAll(String dsl) {
        return FlowDslEngine.defaultEngine().parseAll(dsl);
    }

    /**
     * 将文本 DSL 中的所有 flow 块解析为列表。
     *
     * @param dsl        DSL 文本内容
     * @param sourceName 源码文件名或资源标识
     * @return 流程定义 AST 列表
     */
    public static List<FlowDefinition> parseAll(String dsl, String sourceName) {
        return FlowDslEngine.defaultEngine().parseAll(dsl, sourceName);
    }

    /**
     * 将文本 DSL 中的所有 flow 块解析为列表（同 {@link #parseAll(String)}）。
     *
     * @param dsl DSL 文本内容
     * @return 流程定义 AST 列表
     */
    public static List<FlowDefinition> parseDefinitions(String dsl) {
        return FlowDslEngine.defaultEngine().parseDefinitions(dsl);
    }

    /**
     * 将文本 DSL 中的所有 flow 块解析为列表（同 {@link #parseAll(String, String)}）。
     *
     * @param dsl        DSL 文本内容
     * @param sourceName 源码文件名或资源标识
     * @return 流程定义 AST 列表
     */
    public static List<FlowDefinition> parseDefinitions(String dsl, String sourceName) {
        return FlowDslEngine.defaultEngine().parseDefinitions(dsl, sourceName);
    }

    /**
     * 将文本 DSL 解析并与符号注册表绑定为 {@link BoundFlow}。
     *
     * @param dsl      DSL 文本内容
     * @param registry 符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(String dsl, FlowDefinitionRegistry registry) {
        return FlowDslEngine.defaultEngine().bind(dsl, registry);
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
        return FlowDslEngine.defaultEngine().bindTarget(dsl, targetFlowId, registry);
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
        return FlowDslEngine.defaultEngine().bindTarget(dsl, targetFlowId, registry, resolver);
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
        return FlowDslEngine.defaultEngine().bind(dsl, sourceName, registry);
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
        return FlowDslEngine.defaultEngine().bind(dsl, registry, resolver);
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
        return FlowDslEngine.defaultEngine().bind(dsl, sourceName, registry, resolver);
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
        return FlowDslEngine.defaultEngine().bind(dsl, sourceName, targetFlowId, registry);
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
        return FlowDslEngine.defaultEngine().bind(dsl, sourceName, targetFlowId, registry, resolver);
    }

    /**
     * 将已解析的 {@link FlowDefinition} 与符号注册表绑定为 {@link BoundFlow}。
     *
     * @param definition 流程定义 AST
     * @param registry   符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(FlowDefinition definition, FlowDefinitionRegistry registry) {
        return FlowDslEngine.defaultEngine().bind(definition, registry);
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
        return FlowDslEngine.defaultEngine().bind(definition, registry, resolver);
    }
}
