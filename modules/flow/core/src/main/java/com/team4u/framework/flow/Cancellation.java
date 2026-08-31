package com.team4u.framework.flow;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 协作式取消令牌控制器，支持跨线程协作中断与父子树形级联传播。
 *
 * <p>核心机制与生命周期：
 * <ul>
 *   <li><b>协作感知与物理中断</b>：调用 {@link #cancel()} 不仅将内部标志置位，还会物理中断（{@link Thread#interrupt()}）当前绑定的执行线程；</li>
 *   <li><b>父子级联传播</b>：支持通过 {@link #linked(Cancellation)} 构建父子令牌。当父令牌取消时，所有活跃的子令牌会级联收到取消与线程中断；</li>
 *   <li><b>线程绑定配对</b>：通过 {@code attach(Thread)} 与 {@code detach(Thread)} 由执行引擎在进出工作线程时安全绑定，杜绝线程污染与引用泄漏；</li>
 *   <li><b>信号暴露</b>：通过 {@link #signal()} 暴露轻量级的只读 {@link Signal} 接口，供业务 {@link Operation} 或策略 {@link Policy} 协作轮询或抛出异常。</li>
 * </ul>
 * </p>
 *
 * @author team4u
 */
public final class Cancellation {
    private final Cancellation parent;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    /** 当前绑定且可被中断的运行线程，attach/detach 由内核调用。 */
    private final AtomicReference<Thread> thread = new AtomicReference<Thread>();
    /** 当前令牌的所有活跃子令牌集合（线程安全）。 */
    private final Set<Cancellation> children =
            Collections.newSetFromMap(new ConcurrentHashMap<Cancellation, Boolean>());
    /** 暴露给外部的只读取消信号对象。 */
    private final Signal signal = new Signal() {
        @Override
        public boolean isCancelled() {
            return Cancellation.this.isCancelled();
        }
    };

    /**
     * 内部构造器：支持创建根令牌或关联父令牌。
     *
     * @param parent 可选的父级取消令牌，为 null 表示根令牌
     */
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

    /**
     * 创建独立的顶级根取消令牌。
     *
     * @return 全新的根 {@link Cancellation} 实例
     */
    public static Cancellation create() {
        return new Cancellation(null);
    }

    /**
     * 创建关联指定父令牌的子级取消令牌。
     *
     * @param parent 父级取消令牌，不能为 null
     * @return 派生的子级 {@link Cancellation} 实例
     * @throws NullPointerException 当 {@code parent} 为 null 时抛出
     */
    static Cancellation linked(Cancellation parent) {
        return new Cancellation(Objects.requireNonNull(parent, "parent must not be null"));
    }

    /**
     * 获取关联的只读取消信号视图。
     *
     * @return {@link Signal} 信号实例
     */
    public Signal signal() {
        return signal;
    }

    /**
     * 检查当前令牌或其任意祖先是否已被取消。
     *
     * @return 若已触发取消则返回 true，否则返回 false
     */
    public boolean isCancelled() {
        return cancelled.get() || (parent != null && parent.isCancelled());
    }

    /**
     * 触发取消操作：CAS 置位取消标志、中断当前绑定的运行线程，并级联取消所有子令牌。
     *
     * @return 若本次调用促使取消状态从未取消翻转为已取消则返回 true；若先前已取消则返回 false
     */
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

    /**
     * 将当前执行线程绑定到本取消令牌。若当前已处于取消状态，将立即触发线程中断。
     *
     * @param current 当前执行线程，不能为 null
     * @throws NullPointerException  当 {@code current} 为 null 时抛出
     * @throws IllegalStateException 当已有其他线程绑定在当前令牌上时抛出
     */
    void attach(Thread current) {
        Objects.requireNonNull(current, "thread must not be null");
        if (!thread.compareAndSet(null, current)) {
            throw new IllegalStateException("cancellation is already attached");
        }
        if (isCancelled()) {
            current.interrupt();
        }
    }

    /**
     * 解除当前执行线程与本令牌的绑定。
     *
     * @param current 需解绑的执行线程
     */
    void detach(Thread current) {
        thread.compareAndSet(current, null);
        if (parent != null) {
            parent.children.remove(this);
        }
    }

    /**
     * 幂等解除与父 Cancellation 的链接并清空线程绑定，杜绝强引用泄漏。
     */
    void unlink() {
        thread.set(null);
        if (parent != null) {
            parent.children.remove(this);
        }
    }

    /**
     * 获取当前注册的活跃子令牌数量。
     *
     * @return 子令牌数
     */
    int childCount() {
        return children.size();
    }

    /**
     * 协作式只读取消信号接口。
     */
    public interface Signal {
        /**
         * 检查当前流程是否已收到取消请求。
         *
         * @return 若已取消返回 true，否则返回 false
         */
        boolean isCancelled();

        /**
         * 协作检查方法：若当前已收到取消请求，则立即抛出 {@link CancellationException}。
         *
         * @throws CancellationException 当执行被取消时抛出
         */
        default void throwIfCancelled() {
            if (isCancelled()) {
                throw new CancellationException("flow execution was cancelled");
            }
        }
    }
}

