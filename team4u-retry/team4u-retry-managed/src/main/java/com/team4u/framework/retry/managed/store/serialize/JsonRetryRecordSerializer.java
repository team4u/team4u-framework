package com.team4u.framework.retry.managed.store.serialize;

import com.team4u.framework.serializer.json.JsonUtil;
import com.team4u.framework.retry.managed.store.record.RetryRecord;

/**
 * 基于 JSON 的序列化实现
 */
public class JsonRetryRecordSerializer implements RetryRecordSerializer {

    public static final JsonRetryRecordSerializer INSTANCE = new JsonRetryRecordSerializer();

    @Override
    public String serialize(RetryRecord record) {
        return JsonUtil.toJsonStr(record);
    }

    @Override
    public RetryRecord deserialize(String data) {
        return JsonUtil.toBean(data, RetryRecord.class);
    }
}
