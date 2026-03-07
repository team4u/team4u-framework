package com.team4u.framework.retry.backend.serialize;

import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.exception.RetrySerializationException;

/**
 * 重试任务快照序列化器接口
 */
public interface RetryTaskSnapshotSerializer {

    /**
     * 序列化任务快照
     *
     * @param snapshot 任务快照
     * @return 序列化载荷
     * @throws RetrySerializationException 序列化失败时抛出
     */
    String serialize(RetryTaskSnapshot snapshot) throws RetrySerializationException;

    /**
     * 反序列化任务快照
     *
     * @param payload 序列化载荷
     * @return 任务快照
     * @throws RetrySerializationException 反序列化失败时抛出
     */
    RetryTaskSnapshot deserialize(String payload) throws RetrySerializationException;
}
