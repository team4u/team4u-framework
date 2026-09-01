package com.team4u.framework.flow.definition.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 流程定义元数据（Metadata）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public final class FlowDefinitionMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int schema;
    private final String id;
    private final String version;
    private final String source;
}
