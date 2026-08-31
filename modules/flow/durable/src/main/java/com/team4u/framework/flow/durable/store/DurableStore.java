package com.team4u.framework.flow.durable.store;

import java.util.Optional;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;

/**
 * 耐久化快照持久化存储 SPI 接口（Durable Snapshot Store SPI）。
 *
 * <p>定义快照的只读加载与乐观锁 CAS（Compare-And-Set）原子写入契约：
 * <ul>
 *   <li><b>无副作用加载（{@link #load}）</b>：单纯读取指定 executionId 的最新快照，禁止产生任何状态修改副作用；</li>
 *   <li><b>版本原子更新（{@link #compareAndSet}）</b>：基于单调递增的 revision 版本号进行乐观锁 CAS 写入；
 *       当 {@code expectedRevision == -1L} 时表示仅在记录不存在时创建（Create-If-Absent），用于保证 start 命令的唯一性。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public interface DurableStore {

    /**
     * 根据流程执行 ID 加载最新快照。
     *
     * @param executionId 流程执行实例唯一标识，不能为空
     * @return 存在时返回包含快照的 Optional，不存在时返回 empty
     */
    Optional<DurableSnapshot> load(String executionId);

    /**
     * 基于版本号原子比较并更新快照。
     *
     * @param executionId      流程执行实例唯一标识，不能为空
     * @param expectedRevision 期望的当前版本号（-1 表示创建新记录）
     * @param update           待持久化的新快照对象，不能为 null
     * @return 若当前版本与期望版本一致且成功更新则返回 true，发生并发冲突或版本不匹配时返回 false
     */
    boolean compareAndSet(String executionId, long expectedRevision,
                          DurableSnapshot update);
}

