package com.team4u.framework.flow.spi;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import com.team4u.framework.flow.api.JoinStrategy;
import com.team4u.framework.flow.api.ResumePoint;
import com.team4u.framework.flow.compiler.Compiler;
import com.team4u.framework.flow.compiler.PlanNode;
import com.team4u.framework.flow.model.Outcome;

/**
 * 强类型可执行流程投影访问者 SPI（用于将校验绑定后的 PlanNode 树投影为自定义执行拓扑，如 Durable 运行时引擎）。
 *
 * <p>核心契约：
 * <ul>
 *   <li><b>投影期已完成绑定</b>：遍历已由 {@link Compiler} 校验并解析 Spring Bean 后的 PlanNode 树，所有 {@link ExecutableBinding} 保证持有已实例化的组件对象；</li>
 *   <li><b>拓扑保真</b>：保留完整的树路径、节点标签、作用域名称、并行策略与重试配置；</li>
 *   <li><b>非空约束</b>：各个 visit 方法必须返回非 null 的投影产物对象。</li>
 * </ul>
 * </p>
 *
 * @param <R> 访问者投影产物的目标类型（非 null）
 * @author jay.wu
 */
public interface ExecutableFlowVisitor<R> {

    /**
     * 投影原子业务操作调用节点。
     *
     * @param descriptor 节点静态描述符
     * @param binding    已解析的操作绑定
     * @param project    输入投影函数
     * @param merge      输出合并函数
     * @return 投影结果
     */
    R visitInvoke(NodeDescriptor descriptor,
                  ExecutableBinding binding,
                  Function<Object, Object> project,
                  BiFunction<Object, Object, Object> merge);

    /**
     * 投影顺序流水线/作用域节点。
     *
     * @param descriptor 节点静态描述符
     * @param children   已投影的子节点列表
     * @param scopeName  可选的具名作用域名称
     * @return 投影结果
     */
    R visitSequence(NodeDescriptor descriptor,
                    List<R> children,
                    Optional<String> scopeName);

    /**
     * 投影条件路由节点。
     *
     * @param descriptor      节点静态描述符
     * @param selectorBinding 路由选择器操作绑定
     * @param cases           已投影的条件分支列表
     * @param otherwise       可选的已投影兜底分支
     * @return 投影结果
     */
    R visitRoute(NodeDescriptor descriptor,
                 ExecutableBinding selectorBinding,
                 List<ExecutableRouteCase<R>> cases,
                 Optional<R> otherwise);

    /**
     * 投影降级恢复节点。
     *
     * @param descriptor 节点静态描述符
     * @param trigger    降级触发条件（SKIPPED / FAILED）
     * @param branches   已投影的分支列表
     * @return 投影结果
     */
    R visitFallback(NodeDescriptor descriptor,
                    FallbackTrigger trigger,
                    List<R> branches);

    /**
     * 投影并行并发节点（带汇聚绑定元数据）。
     *
     * @param descriptor  节点静态描述符
     * @param branches    已投影的并行分支列表
     * @param joinBinding 并行汇聚绑定（包含契约、实现类与限定符等元数据）
     * @return 投影结果
     */
    default R visitParallel(NodeDescriptor descriptor,
                            List<ExecutableParallelBranch<R>> branches,
                            ExecutableBinding joinBinding) {
        return visitParallel(descriptor, branches,
                joinBinding != null && joinBinding.instance() instanceof JoinStrategy
                        ? (JoinStrategy<?>) joinBinding.instance()
                        : null);
    }

    /**
     * 投影并行并发节点。
     *
     * @param descriptor 节点静态描述符
     * @param branches   已投影的并行分支列表
     * @param join       并行汇聚策略
     * @return 投影结果
     */
    R visitParallel(NodeDescriptor descriptor,
                    List<ExecutableParallelBranch<R>> branches,
                    JoinStrategy<?> join);

    /**
     * 投影挂起等待节点。
     *
     * @param descriptor  节点静态描述符
     * @param resumePoint 挂起点标识
     * @return 投影结果
     */
    R visitAwait(NodeDescriptor descriptor,
                 ResumePoint<?> resumePoint);

    /**
     * 投影治理控制节点。
     *
     * @param descriptor    节点静态描述符
     * @param kind          控制类型（POLICY / PERSISTENT_POLICY / TIMEOUT 等）
     * @param body          已投影的主体节点
     * @param binding       可选的策略绑定
     * @param keyProjection 策略键提取函数
     * @param configuration 控制配置数据（如 Duration）
     * @return 投影结果
     */
    R visitControl(NodeDescriptor descriptor,
                   ControlKind kind,
                   R body,
                   Optional<ExecutableBinding> binding,
                   Function<Object, Object> keyProjection,
                   Object configuration);

    /**
     * 投影常量/透传终态节点。
     *
     * @param descriptor 节点静态描述符
     * @param outcome    常量输出结果（若有）
     * @param identity   是否为恒等透传
     * @return 投影结果
     */
    R visitComplete(NodeDescriptor descriptor,
                    Outcome<?> outcome,
                    boolean identity);

    /**
     * 投影结构化子流适配节点。
     *
     * @param descriptor 节点静态描述符
     * @param body       适配体执行子计划
     * @param project    输入投影函数
     * @param merge      输出合并函数
     * @return 投影结果
     */
    R visitAdapter(NodeDescriptor descriptor,
                   R body,
                   java.util.function.Function<Object, Object> project,
                   java.util.function.BiFunction<Object, Object, Object> merge);
}

