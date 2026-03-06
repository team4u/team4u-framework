package com.team4u.framework.retry.backend.serialize;

import cn.hutool.json.JSONUtil;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.exception.RetrySerializationException;

/**
 * 基于 Hutool 的任务快照序列化器实现
 */
public class HutoolRetryTaskSnapshotSerializer implements RetryTaskSnapshotSerializer {

    public static final HutoolRetryTaskSnapshotSerializer INSTANCE = new HutoolRetryTaskSnapshotSerializer();

    @Override
    public String serialize(RetryTaskSnapshot snapshot) throws RetrySerializationException {
        try {
            return JSONUtil.toJsonStr(snapshot);
        } catch (Exception e) {
            throw new RetrySerializationException("Failed to serialize retry task snapshot: " + e.getMessage(), e);
        }
    }

    @Override
    public RetryTaskSnapshot deserialize(String payload) throws RetrySerializationException {
        try {
            return JSONUtil.toBean(payload, RetryTaskSnapshot.class);
        } catch (Exception e) {
            throw new RetrySerializationException("Failed to deserialize retry task snapshot: " + e.getMessage(), e);
        }
    }
}
