package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.Objects;

/**
 * 外部流程定义顶层根模型（Flow Definition）。
 *
 * <p>包含 DSL 语法规范版本（schema）、业务流程唯一标识（id）、业务流程定义版本（version）、
 * 流程根规范（root AST）以及源码定位信息（source 与 span）。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class FlowDefinition implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int schema;
    private final String id;
    private final String version;
    private final FlowSpec root;
    private final String source;
    private final SourceSpan span;

    public FlowDefinition(
            int schema,
            String id,
            String version,
            FlowSpec root,
            String source,
            SourceSpan span) {
        this.schema = schema;
        this.id = Objects.requireNonNull(id, "flow id must not be null");
        this.version = version != null ? version : "1";
        this.root = Objects.requireNonNull(root, "flow root must not be null");
        this.source = source;
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    /**
     * 获取元数据。
     *
     * @return 元数据实例
     */
    public FlowDefinitionMetadata metadata() {
        return new FlowDefinitionMetadata(schema, id, version, source);
    }

    /**
     * 获取适用于 Durable 持久化引擎的整型版本号。
     *
     * @return 整型版本号（若无法解析为整数则回退为 1）
     */
    public int durableVersion() {
        try {
            return Integer.parseInt(version.trim());
        } catch (Exception ex) {
            return 1;
        }
    }
}
