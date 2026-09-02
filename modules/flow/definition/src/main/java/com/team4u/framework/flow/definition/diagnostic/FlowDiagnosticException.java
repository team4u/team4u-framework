package com.team4u.framework.flow.definition.diagnostic;

import com.team4u.framework.parser.SourceSpan;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 流程定义、解析、符号解析、类型检查与编译期诊断异常（Flow Diagnostic Exception）。
 *
 * @author jay.wu
 */
@Getter
public class FlowDiagnosticException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final List<Diagnostic> diagnostics;

    public FlowDiagnosticException(List<Diagnostic> diagnostics) {
        super(formatMessage(diagnostics));
        this.diagnostics = diagnostics != null
                ? Collections.unmodifiableList(new ArrayList<Diagnostic>(diagnostics))
                : Collections.<Diagnostic>emptyList();
    }

    public FlowDiagnosticException(Diagnostic diagnostic) {
        this(Collections.singletonList(diagnostic));
    }

    public FlowDiagnosticException(String code, String message) {
        this(new Diagnostic(code, message));
    }

    public FlowDiagnosticException(String code, String message, SourceSpan span) {
        this(new Diagnostic(code, message, span));
    }

    public Diagnostic diagnostic() {
        return diagnostics != null && !diagnostics.isEmpty() ? diagnostics.get(0) : null;
    }

    private static String formatMessage(List<Diagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "Flow definition diagnostic error";
        }
        if (diagnostics.size() == 1) {
            return diagnostics.get(0).format();
        }
        StringBuilder sb = new StringBuilder("Flow definition diagnostic errors (")
                .append(diagnostics.size()).append("):\n");
        for (Diagnostic diagnostic : diagnostics) {
            sb.append("  - ").append(diagnostic.format()).append("\n");
        }
        return sb.toString();
    }
}
