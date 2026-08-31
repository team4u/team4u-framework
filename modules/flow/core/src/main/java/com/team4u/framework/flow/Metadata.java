package com.team4u.framework.flow;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;
import java.util.Optional;

/**
 * 流程节点执行期间的不可变元数据描述。
 *
 * <p>包含流程标识、版本、单次执行实例标识、节点路径以及可选的可读标签：
 * <ul>
 *   <li>{@code flowId}：流程的唯一业务定义标识；</li>
 *   <li>{@code flowVersion}：流程的版本号（非负整数）；</li>
 *   <li>{@code executionId}：单次运行实例的唯一 ID；</li>
 *   <li>{@code nodePath}：当前节点在流程 AST 中的绝对路径标识（如 {@code $.[0]/foo}）；</li>
 *   <li>{@code label}：可选的节点可读显示标签（通过 {@code named(...)} 指定）。</li>
 * </ul>
 * </p>
 *
 * @author team4u
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class Metadata {
    /** 流程定义唯一标识。 */
    private final String flowId;
    /** 流程定义版本号。 */
    private final int flowVersion;
    /** 单次流程执行实例唯一标识。 */
    private final String executionId;
    /** 当前执行节点在流程拓扑树中的绝对路径。 */
    private final String nodePath;
    /** 节点的人类可读标签（可选）。 */
    private final Optional<String> label;

    /**
     * 构造完整的执行元数据对象。
     *
     * @param flowId      流程定义标识，不能为 null 或空白
     * @param flowVersion 流程版本号，必须为非负数
     * @param executionId 单次执行实例标识，不能为 null 或空白
     * @param nodePath    节点路径，不能为 null 或空白
     * @param label       节点标签 Optional 包装，不能为 null（其内部值若存在也不能为空白）
     * @throws NullPointerException     当任何入参为 null 时抛出
     * @throws IllegalArgumentException 当参数为空白或版本号为负数时抛出
     */
    public Metadata(String flowId, int flowVersion, String executionId,
                    String nodePath, Optional<String> label) {
        this.flowId = text(flowId, "flowId");
        if (flowVersion < 0) throw new IllegalArgumentException("flowVersion must not be negative");
        this.flowVersion = flowVersion;
        this.executionId = text(executionId, "executionId");
        this.nodePath = text(nodePath, "nodePath");
        Objects.requireNonNull(label, "label must not be null");
        this.label = label.map(value -> text(value, "label"));
    }

    /**
     * 构造无标签的执行元数据对象。
     *
     * @param flowId      流程定义标识，不能为 null 或空白
     * @param flowVersion 流程版本号，必须为非负数
     * @param executionId 单次执行实例标识，不能为 null 或空白
     * @param nodePath    节点路径，不能为 null 或空白
     * @throws NullPointerException     当入参为 null 时抛出
     * @throws IllegalArgumentException 当参数为空白或版本号为负数时抛出
     */
    public Metadata(String flowId, int flowVersion, String executionId, String nodePath) {
        this(flowId, flowVersion, executionId, nodePath, Optional.empty());
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}

