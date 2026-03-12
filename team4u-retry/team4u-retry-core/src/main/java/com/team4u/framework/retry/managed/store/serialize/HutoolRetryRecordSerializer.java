package com.team4u.framework.retry.managed.store.serialize;

import cn.hutool.json.JSONUtil;
import com.team4u.framework.retry.managed.store.record.RetryRecord;

/**
 * 基于 Hutool 的序列化实现
 */
public class HutoolRetryRecordSerializer implements RetryRecordSerializer {

    public static final HutoolRetryRecordSerializer INSTANCE = new HutoolRetryRecordSerializer();

    @Override
    public String serialize(RetryRecord record) {
        return JSONUtil.toJsonStr(record);
    }

    @Override
    public RetryRecord deserialize(String data) {
        return JSONUtil.toBean(data, RetryRecord.class);
    }
}
