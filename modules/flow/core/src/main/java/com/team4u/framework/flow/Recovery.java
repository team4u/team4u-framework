package com.team4u.framework.flow;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 失败恢复（{@code recoverWith}）分支的不可变输入对包装对象。
 *
 * <p>当主分支执行出现 {@link Failure} 且触发失败恢复逻辑时，系统将保留进入当前失败作用域时的原始输入（Input），
 * 并与捕获的失败诊断（Failure）组合封装为 {@link Recovery} 传递给恢复子流程。
 * <ul>
 *   <li>{@code input}：进入失败作用域时的原始输入数据；</li>
 *   <li>{@code failure}：导致主分支中断失败的诊断信息。</li>
 * </ul>
 * </p>
 *
 * @param <I> 进入失败作用域时的原始输入类型
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class Recovery<I> {
    /** 作用域原始输入数据。 */
    private final I input;
    /** 捕获的失败故障诊断信息。 */
    private final Failure failure;

    /**
     * 构造失败恢复载荷对。
     *
     * @param input   原始输入数据，不能为 null
     * @param failure 失败诊断信息，不能为 null
     * @throws NullPointerException 当任何入参为 null 时抛出
     */
    public Recovery(I input, Failure failure) {
        this.input = Objects.requireNonNull(input, "input must not be null");
        this.failure = Objects.requireNonNull(failure, "failure must not be null");
    }
}

