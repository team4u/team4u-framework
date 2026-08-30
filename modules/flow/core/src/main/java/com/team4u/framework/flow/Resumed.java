package com.team4u.framework.flow;

import java.util.Objects;

/**
 * Await 恢复后的状态与信号：state 为挂起前的 scope entry，signal 为 resume 注入值。
 */
public final class Resumed<S, R> {
    private final S state;
    private final R signal;

    public Resumed(S state, R signal) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.signal = Objects.requireNonNull(signal, "signal must not be null");
    }

    public S state() {
        return state;
    }

    public R signal() {
        return signal;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Resumed<?, ?> resumed = (Resumed<?, ?>) o;
        return state.equals(resumed.state) && signal.equals(resumed.signal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, signal);
    }

    @Override
    public String toString() {
        return "Resumed[state=" + state + ", signal=" + signal + "]";
    }
}
