package com.team4u.framework.flow.definition.type;

import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.model.FlowSpec;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程定义静态类型检查结果（Type Check Result）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
public final class TypeCheckResult {

    private final boolean success;
    private final List<Diagnostic> diagnostics;
    private final TypeRef inputType;
    private final TypeRef outputType;
    private final Map<FlowSpec, TypeRef> specInputTypes;
    private final Map<FlowSpec, TypeRef> specOutputTypes;

    @Builder(toBuilder = true)
    public TypeCheckResult(
            boolean success,
            List<Diagnostic> diagnostics,
            TypeRef inputType,
            TypeRef outputType,
            Map<FlowSpec, TypeRef> specInputTypes,
            Map<FlowSpec, TypeRef> specOutputTypes) {
        this.success = success;
        this.diagnostics = diagnostics != null
                ? Collections.unmodifiableList(new ArrayList<Diagnostic>(diagnostics))
                : Collections.<Diagnostic>emptyList();
        this.inputType = inputType != null ? inputType : TypeRef.ANY;
        this.outputType = outputType != null ? outputType : TypeRef.ANY;
        this.specInputTypes = specInputTypes != null
                ? Collections.unmodifiableMap(new LinkedHashMap<FlowSpec, TypeRef>(specInputTypes))
                : Collections.<FlowSpec, TypeRef>emptyMap();
        this.specOutputTypes = specOutputTypes != null
                ? Collections.unmodifiableMap(new LinkedHashMap<FlowSpec, TypeRef>(specOutputTypes))
                : Collections.<FlowSpec, TypeRef>emptyMap();
    }
}
