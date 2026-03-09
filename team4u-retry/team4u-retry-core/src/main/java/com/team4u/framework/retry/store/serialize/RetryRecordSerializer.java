package com.team4u.framework.retry.store.serialize;

import com.team4u.framework.retry.store.record.RetryRecord;

/**
 * 重试记录序列化器
 */
public interface RetryRecordSerializer {

    String serialize(RetryRecord record);

    RetryRecord deserialize(String data);
}
