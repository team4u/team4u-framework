package com.team4u.framework.flow.compiler;

/**
 * 流程节点拓扑路径段生成工具（Flow Paths）。
 *
 * <p>统一收敛编译器（LogicalLowerers 索引 PlanNode）与结构描述器
 * （desc 包 LogicalDescribers 生成 NodeDescription）两处的路径段拼接规则，保证两条链路对同一棵
 * 逻辑 AST 生成的节点路径完全一致（describe 输出的 path == 编译器索引的 path）。</p>
 *
 * <p>路径段规范：</p>
 * <ul>
 *   <li>根节点路径：{@code $}；</li>
 *   <li>Sequence 第 i 个子节点：{@code <parent>/<i>}；</li>
 *   <li>Route 第 i 个条件分支：{@code <parent>/case:<i>}；</li>
 *   <li>Route 兜底分支：{@code <parent>/otherwise}；</li>
 *   <li>Route 选择器（内部派生 INVOKE 节点）：{@code <parent>/selector}；</li>
 *   <li>Fallback 第 i 个候选分支：{@code <parent>/branch:<i>}；</li>
 *   <li>Parallel 第 i 个并行分支：{@code <parent>/branch:<i>}；</li>
 *   <li>Control 包裹体：{@code <parent>/body}。</li>
 * </ul>
 *
 * @author jay.wu
 */
public final class FlowPaths {
    /** 根节点路径常量。 */
    public static final String ROOT = "$";

    private FlowPaths() { }

    /**
     * 生成根节点路径。
     *
     * @return 根路径 {@code $}
     */
    public static String root() {
        return ROOT;
    }

    /**
     * 生成 Sequence 子节点路径段。
     *
     * @param parentPath 父节点路径
     * @param index      子节点下标（从 0 开始）
     * @return 路径 {@code <parent>/<index>}
     */
    public static String child(String parentPath, int index) {
        return parentPath + "/" + index;
    }

    /**
     * 生成 Route 条件分支路径段。
     *
     * @param parentPath 父节点路径
     * @param index      分支下标（从 0 开始）
     * @return 路径 {@code <parent>/case:<index>}
     */
    public static String routeCase(String parentPath, int index) {
        return parentPath + "/case:" + index;
    }

    /**
     * 生成 Route 兜底分支路径段。
     *
     * @param parentPath 父节点路径
     * @return 路径 {@code <parent>/otherwise}
     */
    public static String routeOtherwise(String parentPath) {
        return parentPath + "/otherwise";
    }

    /**
     * 生成 Route 选择器（内部派生 INVOKE 节点）路径段。
     *
     * @param parentPath 父节点路径
     * @return 路径 {@code <parent>/selector}
     */
    public static String selectorPath(String parentPath) {
        return parentPath + "/selector";
    }

    /**
     * 生成 Fallback 候选分支路径段。
     *
     * @param parentPath 父节点路径
     * @param index      分支下标（从 0 开始）
     * @return 路径 {@code <parent>/branch:<index>}
     */
    public static String fallbackBranch(String parentPath, int index) {
        return parentPath + "/branch:" + index;
    }

    /**
     * 生成 Parallel 并行分支路径段。
     *
     * <p>与 Fallback 候选分支共用 {@code branch:<index>} 段格式：
     * 两类节点不会在同一父节点下同时出现，路径空间不冲突。</p>
     *
     * @param parentPath 父节点路径
     * @param index      分支下标（从 0 开始）
     * @return 路径 {@code <parent>/branch:<index>}
     */
    public static String parallelBranch(String parentPath, int index) {
        return parentPath + "/branch:" + index;
    }

    /**
     * 生成 Control 包裹体路径段。
     *
     * @param parentPath 父节点路径
     * @return 路径 {@code <parent>/body}
     */
    public static String controlBody(String parentPath) {
        return parentPath + "/body";
    }

    /**
     * 生成 Adapter 适配体路径段。
     *
     * @param parentPath 父节点路径
     * @return 路径 {@code <parent>/body}
     */
    public static String adapterBody(String parentPath) {
        return parentPath + "/body";
    }
}
