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
 * 子流程调用规范（Call Spec）。
 *
 * <p>用于在主流程中通过符号引用直接调用已声明或已注册的子流程（Subflow），支持入参投影、结果合并与各类修饰器。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class CallSpec implements FlowSpec {
    private static final long serialVersionUID = 1L;

    private final SymbolRef flow;
    private final ProjectionSpec projectSpec;
    private final MergeSpec mergeSpec;
    private final List<ModifierSpec> modifiers;
    private final SourceSpan span;

    public CallSpec(
            SymbolRef flow,
            ProjectionSpec projectSpec,
            MergeSpec mergeSpec,
            List<ModifierSpec> modifiers,
            SourceSpan span) {
        this.flow = Objects.requireNonNull(flow, "flow must not be null");
        this.projectSpec = projectSpec;
        this.mergeSpec = mergeSpec;
        this.modifiers = modifiers != null
                ? Collections.unmodifiableList(new ArrayList<ModifierSpec>(modifiers))
                : Collections.<ModifierSpec>emptyList();
        this.span = span != null ? span : SourceSpan.UNKNOWN;
    }

    public CallSpec(SymbolRef flow, List<ModifierSpec> modifiers, SourceSpan span) {
        this(flow, (ProjectionSpec) null, null, modifiers, span);
    }

    public CallSpec(SymbolRef flow, SourceSpan span) {
        this(flow, (ProjectionSpec) null, null, Collections.<ModifierSpec>emptyList(), span);
    }

    public CallSpec(SymbolRef flow) {
        this(flow, (ProjectionSpec) null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN);
    }

    /**
     * 获取输入提取投影规范。
     *
     * @return 投影规范（若未配置则返回 null）
     */
    public ProjectionSpec projectSpec() {
        if (projectSpec != null) {
            return projectSpec;
        }
        for (ModifierSpec mod : modifiers) {
            if (mod instanceof ProjectModifierSpec) {
                return ((ProjectModifierSpec) mod).projection();
            }
        }
        return null;
    }

    /**
     * 获取结果合并规范。
     *
     * @return 合并规范（若未配置则返回 null）
     */
    public MergeSpec mergeSpec() {
        if (mergeSpec != null) {
            return mergeSpec;
        }
        for (ModifierSpec mod : modifiers) {
            if (mod instanceof MergeModifierSpec) {
                return ((MergeModifierSpec) mod).merge();
            }
        }
        return null;
    }

    /**
     * 获取输入提取投影符号引用。
     *
     * @return 投影符号引用（若未配置或为属性路径投影则返回 null）
     */
    public SymbolRef project() {
        ProjectionSpec spec = projectSpec();
        if (spec instanceof SymbolRef) {
            return (SymbolRef) spec;
        }
        if (spec instanceof SymbolProjectionSpec) {
            return ((SymbolProjectionSpec) spec).symbol();
        }
        return null;
    }

    /**
     * 获取结果合并符号引用。
     *
     * @return 合并符号引用（若未配置或为属性路径合并则返回 null）
     */
    public SymbolRef merge() {
        MergeSpec spec = mergeSpec();
        if (spec instanceof SymbolRef) {
            return (SymbolRef) spec;
        }
        if (spec instanceof SymbolMergeSpec) {
            return ((SymbolMergeSpec) spec).symbol();
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
     * 获取展示标签名称。
     *
     * @return 标签名称（若未配置则返回 null）
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
     * 获取展示标签名称。
     *
     * @return 标签名称（若未配置则返回 null）
     */
    public String name() {
        return named();
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
