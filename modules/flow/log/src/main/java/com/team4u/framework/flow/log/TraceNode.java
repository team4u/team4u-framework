package com.team4u.framework.flow.log;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 流程执行链路树节点数据载体。
 *
 * @author jay.wu
 */
@Getter
@Setter
public class TraceNode {

    private final String path;
    private String label;
    private long startTime;
    private long durationMs;
    private String outcome = "UNKNOWN";
    private String extra = "";

    private final List<TraceNode> children = Collections.synchronizedList(new ArrayList<TraceNode>());

    public TraceNode(String path, String label) {
        this.path = path;
        this.label = label;
    }

    /**
     * 线程安全添加子节点。
     *
     * @param child 子节点
     */
    public void addChild(TraceNode child) {
        if (child != null) {
            children.add(child);
        }
    }

    /**
     * 获取子节点快照列表。
     *
     * @return 子节点只读列表
     */
    public List<TraceNode> snapshotChildren() {
        synchronized (children) {
            return new ArrayList<TraceNode>(children);
        }
    }
}
