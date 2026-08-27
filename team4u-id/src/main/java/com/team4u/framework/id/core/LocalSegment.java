package com.team4u.framework.id.core;

import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.SpaceKey;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地号段：一次批量取号、本地零竞争发号
 * <p>
 * 惰性取段：本地段耗尽时才访问上游计数器（{@code incrementAndGet(key, size)}），
 * 无生产者线程、无清理器——实例生命周期由 {@code SequenceService} 的 LRU 缓存管理，
 * 分组轮转或规则变更后旧实例自然淘汰，段内未用完的序号作废（号段模式的固有空洞）。
 * </p>
 * 并发模型：读路径以 {@code cursor} CAS 无锁发号；段切换在实例锁内串行取段，
 * 取段返回值即段边界，天然 singleflight。
 *
 * @author jay.wu
 */
@RequiredArgsConstructor
class LocalSegment {

    private final CounterCapable counter;
    private final SpaceKey key;

    /**
     * 号段长度
     */
    private final int size;

    /**
     * 本地段已发出的最大计数位置（1-based，0 表示尚未发出）
     */
    private final AtomicLong cursor = new AtomicLong(0);

    /**
     * 本地段上界（含）；0 表示尚未取段
     */
    private volatile long end = 0;

    /**
     * 上游已耗尽（设置上限且未循环），后续调用直接拒绝，不再访问存储
     */
    private volatile boolean exhausted = false;

    /**
     * 取下一个计数位置
     *
     * @param count   总可用数量（null 表示无限）
     * @param recycle 达到上限后循环
     * @return 1-based 计数位置；上游耗尽返回 {@code null}
     */
    Long next(Long count, boolean recycle) {
        while (true) {
            if (exhausted) {
                return null;
            }
            long current = cursor.get();
            if (current < end) {
                if (!cursor.compareAndSet(current, current + 1)) {
                    continue;
                }
                long position = current + 1;
                if (count != null && !recycle && position > count) {
                    // 号段越过耗尽线：标记耗尽，剩余位置作废
                    exhausted = true;
                    return null;
                }
                return position;
            }
            synchronized (this) {
                if (exhausted) {
                    return null;
                }
                if (cursor.get() < end) {
                    // 并发取段已完成
                    continue;
                }
                long total = counter.incrementAndGet(key, size);
                long base = total - size;
                if (count != null && !recycle && base >= count) {
                    exhausted = true;
                    return null;
                }
                // cursor 在取段时刻必然已推进到 base（读者最多 CAS 到旧 end==base），
                // 不得回写 cursor：end 发布后读者的 CAS 前先回写会回退游标导致重复发号
                end = total;
            }
        }
    }
}
