package com.team4u.framework.criterion.trace;

import lombok.Data;
import com.team4u.framework.criterion.model.Criterion;

import java.util.ArrayList;
import java.util.List;

/**
 * 表达式追踪节点
 * 用于存储树状结构的执行日志（节点信息、输入值、输出结果）
 */
@Data
public class TraceNode {

    /**
     * 节点 ID (通常对应 Criterion 的类名或类型)
     */
    private String type;

    /**
     * 节点描述 (如 "age > 18")
     */
    private String description;

    /**
     * 输入的上下文实际值 (actual)
     */
    private Object input;

    /**
     * 匹配/计算是否成功
     */
    private boolean matched;

    /**
     * 子节点列表
     */
    private List<TraceNode> children = new ArrayList<>();

    /**
     * 原始规则对象 (用于调试或生成更详细的描述)
     */
    private transient Criterion criterion;

    public TraceNode(Criterion criterion, Object input) {
        this.criterion = criterion;
        this.type = criterion.getClass().getSimpleName();
        this.input = input;
        this.description = criterion.toString();
    }

    /**
     * 添加子节点
     *
     * @param child 子追踪节点
     */
    public void addChild(TraceNode child) {
        children.add(child);
    }

    /**
     * 打印追踪链
     */
    public String render() {
        return new TraceTreeRenderer().render(this);
    }
}