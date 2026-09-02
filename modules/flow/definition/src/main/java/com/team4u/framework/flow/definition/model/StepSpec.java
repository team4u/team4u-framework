package com.team4u.framework.flow.definition.model;

import com.team4u.framework.parser.SourceSpan;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 业务原子步骤配置规范（Step Spec）。
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class StepSpec implements FlowSpec {
    private static final long serialVersionUID = 1L;

    private final SymbolRef operation;
    private final SymbolRef project;
    private final SymbolRef merge;
    private final List<ModifierSpec> modifiers;
    private final SourceSpan span;

    public StepSpec(
            SymbolRef operation,
            SymbolRef project,
            SymbolRef merge,
            List<ModifierSpec> modifiers,
            SourceSpan span) {
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        this.project = project;
        this.merge = merge;
        this.modifiers = modifiers != null
                ? Collections.unmodifiableList(new ArrayList<ModifierSpec>(modifiers))
                : Collections.<ModifierSpec>emptyList();
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public StepSpec(SymbolRef operation, List<ModifierSpec> modifiers, SourceSpan span) {
        this(operation, null, null, modifiers, span);
    }

    public StepSpec(SymbolRef operation, SourceSpan span) {
        this(operation, null, null, Collections.<ModifierSpec>emptyList(), span);
    }

    public StepSpec(SymbolRef operation) {
        this(operation, null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN);
    }

    /**
     * 获取输入提取投影符号引用。
     *
     * @return 投影符号引用（若未配置则返回 null）
     */
    public SymbolRef project() {
        if (project != null) {
            return project;
        }
        for (ModifierSpec mod : modifiers) {
            if (mod instanceof ProjectModifierSpec) {
                return ((ProjectModifierSpec) mod).projector();
            }
        }
        return null;
    }

    /**
     * 获取结果合并符号引用。
     *
     * @return 合并符号引用（若未配置则返回 null）
     */
    public SymbolRef merge() {
        if (merge != null) {
            return merge;
        }
        for (ModifierSpec mod : modifiers) {
            if (mod instanceof MergeModifierSpec) {
                return ((MergeModifierSpec) mod).merger();
            }
        }
        return null;
    }

    /**
     * 是否声明了 optional 修饰。
     *
     * @return 若声明为可选则返回 true，否则返回 false
     */
    public boolean isOptional() {
        for (ModifierSpec mod : modifiers) {
            if (mod instanceof OptionalModifierSpec) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取超时时长配置。
     *
     * @return 超时时长（若未配置则返回 null）
     */
    public Duration timeout() {
        for (ModifierSpec mod : modifiers) {
            if (mod instanceof TimeoutModifierSpec) {
                return ((TimeoutModifierSpec) mod).duration();
            }
        }
        return null;
    }

    /**
     * 获取步骤命名标签。
     *
     * @return 命名标签（若未配置则返回 null）
     */
    public String named() {
        for (ModifierSpec mod : modifiers) {
            if (mod instanceof NamedModifierSpec) {
                return ((NamedModifierSpec) mod).name();
            }
        }
        return null;
    }

    /**
     * 获取全部策略治理修饰器列表。
     *
     * @return 策略修饰器只读列表
     */
    public List<PolicyModifierSpec> policies() {
        List<PolicyModifierSpec> list = new ArrayList<PolicyModifierSpec>();
        for (ModifierSpec mod : modifiers) {
            if (mod instanceof PolicyModifierSpec) {
                list.add((PolicyModifierSpec) mod);
            }
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 获取全部重试治理修饰器列表。
     *
     * @return 重试修饰器只读列表
     */
    public List<RetryModifierSpec> retries() {
        List<RetryModifierSpec> list = new ArrayList<RetryModifierSpec>();
        for (ModifierSpec mod : modifiers) {
            if (mod instanceof RetryModifierSpec) {
                list.add((RetryModifierSpec) mod);
            }
        }
        return Collections.unmodifiableList(list);
    }
}
