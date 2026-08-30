package com.team4u.framework.flow.durable;

/**
 * 流程快照存储 SPI：提供按 execution key 加载快照，以及按 expected revision 执行 CAS 提交。
 *
 * @author jay.wu
 */
public interface DurableStore {

    /**
     * 加载流程执行快照。
     *
     * @param flowId      流程 ID
     * @param executionId 执行 ID
     * @return 快照实例，若不存在返回 null
     */
    DurableSnapshot load(String flowId, String executionId);

    /**
     * 以 CAS 乐观并发检查保存快照。
     *
     * @param snapshot         新快照
     * @param expectedRevision 期望的前置 revision（若首次创建则通常为 0）
     * @return true 表示 CAS 成功，false 表示 revision 冲突或已经被其它执行者修改
     */
    boolean save(DurableSnapshot snapshot, long expectedRevision);
}
