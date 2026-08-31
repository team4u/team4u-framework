package com.team4u.framework.flow;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 封装在 ForkJoinPool 环境下的 ManagedBlocker 补偿阻塞调用，
 * 防止在有限并行度或单核 ForkJoinPool（如 ForkJoinPool.commonPool()）下发生嵌套饥饿死锁。
 */
final class ManagedBlockers {
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
