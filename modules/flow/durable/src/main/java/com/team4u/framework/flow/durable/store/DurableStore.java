package com.team4u.framework.flow.durable.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;

/**
 * 耐久化快照持久化存储 SPI 接口（Durable Snapshot Store SPI）。
 *
 * <p>定义快照的只读加载与乐观锁 CAS（Compare-And-Set）原子写入契约：
 * <ul>
 *   <li><b>无副作用加载（{@link #load}）</b>：单纯读取指定 executionId 的最新快照，禁止产生任何状态修改副作用；</li>
 *   <li><b>版本原子更新（{@link #compareAndSet}）</b>：基于单调递增的 revision 版本号进行乐观锁 CAS 写入；
 *       当 {@code expectedRevision == -1L} 时表示仅在记录不存在时创建（Create-If-Absent），用于保证 start 命令的唯一性；</li>
 *   <li><b>可选到期扫描（{@link #scanDue}）</b>：扫描已到达定时唤醒时刻（firstWakeAt）的活跃快照，
 *       供外部定时调度器驱动 recover；默认不支持（返回 empty）。</li>
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

    /**
     * 扫描已到达定时唤醒时刻的活跃（ACTIVE）快照，供外部定时调度器驱动 recover。
     *
     * <p>仅 {@code firstWakeAt <= now} 的快照被返回（快照信封的 firstWakeAt 冗余字段由引擎编码时填充）。
     * 实现应按 firstWakeAt 升序返回最多 {@code limit} 条，供调度器逐一调用 {@code recover(executionId)}。
     * 并发安全由 CAS 乐观锁保证：多个调度器扫描到同一到期执行时，仅一方 recover 成功，另一方得到 REVISION_CONFLICT。</p>
     *
     * <p>默认不支持扫描（返回 empty）：不实现该能力的存储需要外部索引（如按到期时间的独立队列）
 *      或全量键遍历配合过滤实现定时唤醒调度。</p>
     *
     * @param now  当前时刻
     * @param limit 单次返回的最大条数（正数）
     * @return 到期快照列表；存储不支持扫描能力时返回 empty
     */
    default Optional<List<DurableSnapshot>> scanDue(Instant now, int limit) {
        return Optional.empty();
    }
}

