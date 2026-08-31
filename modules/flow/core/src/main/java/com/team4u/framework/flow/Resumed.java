package com.team4u.framework.flow;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 流程异步挂起（Await）被恢复（Resume）后的载荷对包装对象。
 *
 * <p>将挂起前捕获的当前作用域状态（State）与外部恢复时传入的动态响应信号（Signal）绑定在一起，
 * 作为后续步骤的输入参数。
 * <ul>
 *   <li>{@code state}：挂起点执行前的当前作用域状态/输入数据；</li>
 *   <li>{@code signal}：恢复执行时由外部系统/用户传入的业务回调数据或事件载荷。</li>
 * </ul>
 * </p>
 *
 * @param <S> 挂起前当前作用域状态类型
 * @param <R> 恢复时传入的外部信号数据类型
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class Resumed<S, R> {
    /** 挂起点捕获的先前状态数据。 */
    private final S state;
    /** 恢复时注入的外部信号数据。 */
    private final R signal;

    /**
     * 构造恢复状态对。
     *
     * @param state  挂起点捕获的状态，不能为 null
     * @param signal 恢复时注入的信号，不能为 null
     * @throws NullPointerException 当任何入参为 null 时抛出
     */
    public Resumed(S state, R signal) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.signal = Objects.requireNonNull(signal, "signal must not be null");
    }
}

