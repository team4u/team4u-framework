package com.team4u.framework.criterion.trace;

import lombok.Getter;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 追踪记录器
 * 用于在运行时动态构建追踪树，维护父子节点的栈结构
 */
public class TraceRecorder {

    /**
     * 使用栈来维护当前的父节点
     */
    private final Deque<TraceNode> stack = new ArrayDeque<>();
    @Getter
    private TraceNode root;

    /**
     * 开始记录一个节点
     *
     * @param node 追踪节点
     */
    public void begin(TraceNode node) {
        if (root == null) {
            root = node;
        } else if (!stack.isEmpty()) {
            stack.peek().addChild(node);
        }
        stack.push(node);
    }

    /**
     * 结束记录当前节点
     *
     * @param result 执行结果
     */
    public void end(boolean result) {
        if (stack.isEmpty()) {
            return;
        }
        TraceNode node = stack.pop();
        node.setMatched(result);
    }

    /**
     * 获取当前栈顶节点（用于调试或高级用法）
     *
     * @return 栈顶节点，如果栈为空则返回 null
     */
    public TraceNode peek() {
        return stack.peek();
    }

    /**
     * 判断是否正在记录中
     *
     * @return 是否正在记录
     */
    public boolean isRecording() {
        return root != null;
    }
}
