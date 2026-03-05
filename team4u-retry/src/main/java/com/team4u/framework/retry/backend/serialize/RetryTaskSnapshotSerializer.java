package com.team4u.framework.retry.backend.serialize;

import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.exception.RetrySerializationException;

/**
 * 重试任务快照序列化器
 */
public interface RetryTaskSnapshotSerializer {

    /**
     * 序列化快照对象
     *
     * @param snapshot 快照对象
     * @return 序列化字符串
     * @throws RetrySerializationException 序列化失败
     */
    String serialize(RetryTaskSnapshot snapshot) throws RetrySerializationException;

    /**
     * 反序列化快照对象
     *
     * @param payload 序列化字符串
     * @return 快照对象
     * @throws RetrySerializationException 反序列化失败
     */
    RetryTaskSnapshot deserialize(String payload) throws RetrySerializationException;
}
