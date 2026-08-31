package com.team4u.framework.flow;

import lombok.AllArgsConstructor;

import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * 遍历 Logical 树并生成 {@link NodeDescription} 树的内部结构构建器。
 *
 * <p>采用显式工作栈后序构建算法，结合 {@link LogicalDescriberRegistry} 策略注册表，
 * 确保在面对任意超深层级嵌套时不会发生 JVM 栈溢出（StackOverflowError）。</p>
 *
 * @author jay.wu
 */
final class FlowDescriptionBuilder {
    private FlowDescriptionBuilder() { }

    @AllArgsConstructor
    static final class WorkItem {
        final Logical logical;
        final String path;
        final String label;
        final boolean build;
    }

    /**
     * 遍历逻辑 AST 并生成结构化描述树。
     *
     * @param rootLogical 逻辑 AST 根节点
     * @param rootPath    根节点路径（通常为 {@code "$"}）
     * @return 节点描述树
     */
    static NodeDescription describe(Logical rootLogical, String rootPath) {
        ArrayDeque<WorkItem> workStack = new ArrayDeque<WorkItem>();
        ArrayList<NodeDescription> resultStack = new ArrayList<NodeDescription>();

        workStack.addLast(new WorkItem(rootLogical, rootPath, null, false));

        while (!workStack.isEmpty()) {
            WorkItem item = workStack.removeLast();
            Logical logical = item.logical;
            String path = item.path;

            if (item.build) {
                NodeDescription built = buildDescription(logical, path, item.label, resultStack);
                resultStack.add(built);
                continue;
            }

            String label = item.label;
            while (logical instanceof Logical.Named) {
                Logical.Named named = (Logical.Named) logical;
                label = named.label();
                logical = named.body();
            }

            LogicalDescriber<Logical> describer = getDescriber(logical);
            if (describer.isLeaf()) {
                NodeDescription built = describer.build(logical, path, label, resultStack);
                resultStack.add(built);
            } else {
                workStack.addLast(new WorkItem(logical, path, label, true));
                describer.pushChildren(logical, path, workStack);
            }
        }

        if (resultStack.size() != 1) {
            throw new IllegalStateException("Expected exactly 1 root description on stack, but got: " + resultStack.size());
        }
        return resultStack.remove(resultStack.size() - 1);
    }

    private static NodeDescription buildDescription(Logical logical, String path, String label,
                                                     ArrayList<NodeDescription> resultStack) {
        LogicalDescriber<Logical> describer = getDescriber(logical);
        return describer.build(logical, path, label, resultStack);
    }

    @SuppressWarnings("unchecked")
    private static LogicalDescriber<Logical> getDescriber(Logical logical) {
        return (LogicalDescriber<Logical>) LogicalDescriberRegistry.global().get(logical.getClass())
                .orElseThrow(() -> new IllegalStateException("Unknown logical node: " + logical.getClass()));
    }
}
