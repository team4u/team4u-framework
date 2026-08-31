package com.team4u.framework.flow.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import com.team4u.framework.flow.spi.ExecutableFlowVisitor;

/**
 * 工作列表后序迭代遍历 PlanNode 树并调用 {@link ExecutableFlowVisitor} 生成投影产物的内部投射器。
 *
 * <p>设计与算法保证：
 * <ul>
 *   <li><b>非递归堆栈遍历</b>：采用显式堆栈工作列表（Explicit Work Stack）结合 {@link PlanNodeProjectorRegistry} 策略注册表实现后序遍历（Post-order Traversal），避免深度嵌套时发生 JVM 方法栈溢出；</li>
 *   <li><b>严格保序与非空</b>：确保子节点投影出栈顺序与 AST 原始声明顺序一致，并强制校验访问者产物非 null。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public final class ExecutableProjector {
    private ExecutableProjector() { }

    /**
     * 工作任务元素。
     */
    static final class WorkItem {
        final PlanNode node;
        final boolean build;

        WorkItem(PlanNode node, boolean build) {
            this.node = node;
            this.build = build;
        }
    }

    /**
     * 执行非递归投影。
     *
     * @param root    已编译校验的 PlanNode 根节点，不能为 null
     * @param visitor 投影访问者，不能为 null
     * @param <R>     产物类型
     * @return 最终根节点投影产物
     */
    public static <R> R project(PlanNode root, ExecutableFlowVisitor<R> visitor) {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(visitor, "visitor must not be null");

        ArrayDeque<WorkItem> workStack = new ArrayDeque<WorkItem>();
        ArrayList<R> resultStack = new ArrayList<R>();

        workStack.addLast(new WorkItem(root, false));

        while (!workStack.isEmpty()) {
            WorkItem current = workStack.removeLast();
            PlanNode node = current.node;

            if (current.build) {
                R projected = Objects.requireNonNull(buildNode(node, resultStack, visitor),
                        "visitor must not return null for node " + node.descriptor().path());
                resultStack.add(projected);
                continue;
            }

            // 压入 build 标记任务
            workStack.addLast(new WorkItem(node, true));

            // 委托 Projector 策略将子节点逆序压入工作列表
            pushChildren(node, workStack);
        }

        if (resultStack.size() != 1) {
            throw new IllegalStateException("Expected exactly 1 projection result on stack, but got: " + resultStack.size());
        }
        return resultStack.remove(resultStack.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private static void pushChildren(PlanNode node, ArrayDeque<WorkItem> workStack) {
        PlanNodeProjector<PlanNode> projector = (PlanNodeProjector<PlanNode>) PlanNodeProjectorRegistry.global()
                .get(node.getClass())
                .orElseThrow(() -> new IllegalStateException("Unknown PlanNode: " + node.getClass()));
        projector.pushChildren(node, workStack);
    }

    @SuppressWarnings("unchecked")
    private static <R> R buildNode(PlanNode node, ArrayList<R> resultStack, ExecutableFlowVisitor<R> visitor) {
        PlanNodeProjector<PlanNode> projector = (PlanNodeProjector<PlanNode>) PlanNodeProjectorRegistry.global()
                .get(node.getClass())
                .orElseThrow(() -> new IllegalStateException("Unknown PlanNode: " + node.getClass()));
        return projector.build(node, resultStack, visitor);
    }
}
