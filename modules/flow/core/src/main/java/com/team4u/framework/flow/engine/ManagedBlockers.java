package com.team4u.framework.flow.engine;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ForkJoinPool 协作阻塞补偿工具类（ManagedBlockers Helper）。
 *
 * <p>封装 {@link ForkJoinPool#managedBlock(ForkJoinPool.ManagedBlocker)} 补偿调用，在 ForkJoinPool 环境下发生同步等待（如 CountDownLatch 等待、阻塞队列出队、Future 结果获取）时通知池动态补偿新工作线程，防止在小线程池或单核环境下发生死锁与线程饥饿。</p>
 *
 * @author jay.wu
 */
public final class ManagedBlockers {
    private ManagedBlockers() { }


    static void await(final CountDownLatch latch) throws InterruptedException {
        ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
            @Override
            public boolean block() throws InterruptedException {
                latch.await();
                return true;
            }

            @Override
            public boolean isReleasable() {
                return latch.getCount() == 0;
            }
        });
    }

    static <E> E take(final BlockingQueue<E> queue) throws InterruptedException {
        class TakeBlocker implements ForkJoinPool.ManagedBlocker {
            private E value;
            private boolean done;

            @Override
            public boolean block() throws InterruptedException {
                value = queue.take();
                done = true;
                return true;
            }

            @Override
            public boolean isReleasable() {
                if (done) return true;
                value = queue.poll();
                if (value != null) {
                    done = true;
                    return true;
                }
                return false;
            }
        }
        TakeBlocker blocker = new TakeBlocker();
        ForkJoinPool.managedBlock(blocker);
        return blocker.value;
    }

    static <E> E poll(final BlockingQueue<E> queue, Duration timeout) throws InterruptedException {
        final long deadlineNanos = System.nanoTime() + timeout.toNanos();
        class QueueBlocker implements ForkJoinPool.ManagedBlocker {
            private E value;
            private boolean done;

            @Override
            public boolean block() throws InterruptedException {
                while (!done) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        done = true;
                        break;
                    }
                    value = queue.poll(remaining, TimeUnit.NANOSECONDS);
                    if (value != null || (deadlineNanos - System.nanoTime() <= 0)) {
                        done = true;
                    }
                }
                return true;
            }

            @Override
            public boolean isReleasable() {
                if (done) return true;
                value = queue.poll();
                if (value != null || (deadlineNanos - System.nanoTime() <= 0)) {
                    done = true;
                    return true;
                }
                return false;
            }
        }
        QueueBlocker blocker = new QueueBlocker();
        ForkJoinPool.managedBlock(blocker);
        return blocker.value;
    }

    static <T> T get(final Future<T> future, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        final long deadlineNanos = System.nanoTime() + timeout.toNanos();
        class FutureBlocker implements ForkJoinPool.ManagedBlocker {
            private boolean done;

            @Override
            public boolean block() throws InterruptedException {
                while (!done) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        done = true;
                        break;
                    }
                    try {
                        future.get(remaining, TimeUnit.NANOSECONDS);
                        done = true;
                    } catch (TimeoutException e) {
                        done = true;
                    } catch (ExecutionException e) {
                        done = true;
                    }
                }
                return true;
            }

            @Override
            public boolean isReleasable() {
                if (done || future.isDone() || (deadlineNanos - System.nanoTime() <= 0)) {
                    done = true;
                    return true;
                }
                return false;
            }
        }
        FutureBlocker blocker = new FutureBlocker();
        ForkJoinPool.managedBlock(blocker);
        if (!future.isDone()) {
            throw new TimeoutException("Timeout waiting for future");
        }
        return future.get();
    }
}
