package com.team4u.framework.flow;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 协作式取消令牌，同时中断当前绑定的运行线程。支持父子链接：父取消则向所有子令牌级联传播取消与中断。
 * 单个 Cancellation 最多 attach 一个线程，由 SerialMachine 在进入/退出时配对调用。
 */
public final class Cancellation {
    private final Cancellation parent;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    // 当前绑定且可被中断的运行线程，attach/detach 由内核调用
    private final AtomicReference<Thread> thread = new AtomicReference<Thread>();
    private final Set<Cancellation> children =
            Collections.newSetFromMap(new ConcurrentHashMap<Cancellation, Boolean>());
    private final Signal signal = new Signal() {
        @Override
        public boolean isCancelled() {
            return Cancellation.this.isCancelled();
        }
    };

    private Cancellation(Cancellation parent) {
        this.parent = parent;
        if (parent != null) {
            if (parent.isCancelled()) {
                cancelled.set(true);
            } else {
                parent.children.add(this);
                // 再次检查父是否在注册窗口被取消
                if (parent.isCancelled()) {
                    cancel();
                }
            }
        }
    }

    public static Cancellation create() {
        return new Cancellation(null);
    }

    static Cancellation linked(Cancellation parent) {
        return new Cancellation(Objects.requireNonNull(parent, "parent must not be null"));
    }

    public Signal signal() {
        return signal;
    }

    /** 本令牌或任一祖先已取消时返回 true。 */
    public boolean isCancelled() {
        return cancelled.get() || (parent != null && parent.isCancelled());
    }

    /** CAS 置位取消标志并中断当前绑定线程，级联取消所有子令牌，返回是否本次发生状态翻转。 */
    public boolean cancel() {
        boolean changed = cancelled.compareAndSet(false, true);
        Thread current = thread.get();
        if (current != null) {
            current.interrupt();
        }
        for (Cancellation child : children) {
            child.cancel();
        }
        return changed;
    }

    void attach(Thread current) {
        Objects.requireNonNull(current, "thread must not be null");
        if (!thread.compareAndSet(null, current)) {
            throw new IllegalStateException("cancellation is already attached");
        }
        if (isCancelled()) {
            current.interrupt();
        }
    }

    void detach(Thread current) {
        thread.compareAndSet(current, null);
        if (parent != null) {
            parent.children.remove(this);
        }
    }

    /** 幂等解除与父 Cancellation 的链接并清空线程绑定，杜绝强引用泄漏。 */
    void unlink() {
        thread.set(null);
        if (parent != null) {
            parent.children.remove(this);
        }
    }

    int childCount() {
        return children.size();
    }

    /**
     * 回调式取消信号。{@link #throwIfCancelled()} 在已取消时抛出 CancellationException，
     * 供 Operation/Policy 协作感知取消。
     */
    public interface Signal {
        boolean isCancelled();

        default void throwIfCancelled() {
            if (isCancelled()) {
                throw new CancellationException("flow execution was cancelled");
            }
        }
    }
}
