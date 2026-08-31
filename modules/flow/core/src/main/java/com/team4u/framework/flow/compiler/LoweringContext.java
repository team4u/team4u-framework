package com.team4u.framework.flow.compiler;

import java.util.Map;
import java.util.Set;
import com.team4u.framework.flow.api.ResumePoint;

/**
 * 逻辑 AST 降级编译上下文。
 *
 * @author jay.wu
 */
public interface LoweringContext {

    /**
     * 获取指定路径的已编译 PlanNode。
     *
     * @param path 节点路径
     * @return 编译后的 PlanNode
     */
    PlanNode required(String path);

    /**
     * 解析组件绑定为运行时 BoundTarget。
     *
     * @param binding 逻辑绑定信息
     * @param path    节点路径
     * @return 解析后的目标
     */
    PlanNode.BoundTarget resolve(Logical.Binding binding, String path);

    /**
     * 编译 Invoke 逻辑节点。
     *
     * @param invoke 逻辑调用节点
     * @param path   节点路径
     * @param label  可选标签
     * @return 编译后的 Invoke PlanNode
     */
    PlanNode.Invoke invoke(Logical.Invoke invoke, String path, String label);

    /**
     * 记录编译静态拓扑或类型问题。
     *
     * @param code    错误码
     * @param path    节点路径
     * @param message 错误信息
     */
    void problem(String code, String path, String message);

    /**
     * 已注册的具名 Scope 名称集合。
     */
    Set<String> scopeNames();

    /**
     * 开启一个新的并行块分支名校验作用域。
     *
     * <p>分支名称（Token）仅在同一个并行块内必须唯一；不同并行块（包括嵌套并行块）
     * 允许复用相同名称。开启新作用域后，{@link #branchNames()} 返回当前块内已注册的名称集合，
     * {@link #endParallelBlock()} 恢复外层作用域。</p>
     */
    void beginParallelBlock();

    /**
     * 结束当前并行块分支名校验作用域（与 {@link #beginParallelBlock()} 严格配对调用）。
     */
    void endParallelBlock();

    /**
     * 当前并行块作用域内已注册的分支名称集合（仅在 {@link #beginParallelBlock()} 与
     * {@link #endParallelBlock()} 之间调用有效）。
     */
    Set<String> branchNames();

    /**
     * 已注册的挂起点字典。
     */
    Map<String, ResumePoint<?>> resumePoints();

    /**
     * 路径索引字典。
     */
    Map<String, PlanNode> byPath();
}
