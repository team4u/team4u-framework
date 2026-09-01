package com.team4u.framework.flow.dsl;

import com.team4u.framework.flow.definition.binding.BoundFlow;
import com.team4u.framework.flow.definition.binding.FlowBinder;
import com.team4u.framework.flow.definition.model.FlowDefinition;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.dsl.parser.FlowDslParser;
import com.team4u.framework.flow.spi.OperationResolver;

import java.util.Objects;

/**
 * 流程文本 DSL 核心统一门面入口（Flow DSL Facade）。
 *
 * <p>提供文本 DSL 语法解析、类型校验、符号解析绑定与 Local/Durable 可执行流编译的一站式服务。</p>
 *
 * @author jay.wu
 */
public final class FlowDsl {

    private FlowDsl() { }

    /**
     * 将文本 DSL 解析为外部配置模型 {@link FlowDefinition}。
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
     * 将文本 DSL 解析并与符号注册表绑定为 {@link BoundFlow}。
     *
     * @param dsl      DSL 文本内容
     * @param registry 符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(String dsl, FlowDefinitionRegistry registry) {
        return bind(dsl, null, registry, OperationResolver.rejecting());
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
        return bind(dsl, sourceName, registry, OperationResolver.rejecting());
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
        return bind(dsl, null, registry, resolver);
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
        FlowDefinition definition = parse(dsl, sourceName);
        return FlowBinder.bind(definition, registry, resolver);
    }

    /**
     * 将已解析的 {@link FlowDefinition} 与符号注册表绑定为 {@link BoundFlow}。
     *
     * @param definition 流程定义 AST
     * @param registry   符号注册表
     * @return 绑定后的 BoundFlow
     */
    public static BoundFlow bind(FlowDefinition definition, FlowDefinitionRegistry registry) {
        return FlowBinder.bind(definition, registry, OperationResolver.rejecting());
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
