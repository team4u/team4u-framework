package com.team4u.framework.kv;

import java.util.List;

/**
 * 扫描与过期清理能力
 * <p>
 * 为清理器（{@code team4u-kv-lifecycle} 的 KvCleaner）与轮询订阅
 * （PollingWatcher）提供挂载点。不支持扫描语义的存储可不实现。
 * </p>
 *
 * @author jay.wu
 */
public interface ScanCapable {

    /**
     * 扫描指定键空间下所有存活记录的键
     * <p>
     * 大键量存储（如 Redis keys 扫描）成本较高，调用方应控制使用频率。
     * </p>
     */
    List<SpaceKey> scan(String space);

    /**
     * 物理清理指定键空间下已过期的记录
     *
     * @param maxBatch  单次最大删除数量，防止长事务/大删除阻塞存储
     * @return 实际删除数量（尽力而为，惰性过期的存储可能返回 0）
     */
    int pruneExpired(String space, int maxBatch);
}
