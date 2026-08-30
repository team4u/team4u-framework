package com.team4u.framework.retry.managed.store.serialize;

import com.team4u.framework.retry.managed.store.record.RetryRecord;

/**
 * 基于 JSON 的序列化实现
 *
 * @deprecated 直接 JSON 序列化 {@link RetryRecord} 会把抽象 {@code Backoff} 字段
 *     按实现类实例化，durable 恢复后得到的 Backoff 实例不可靠（且依赖默认构造），
 *     已被版本化实现 {@link VersionedRetryRecordSerializer} 取代。
 *     本类保留为兼容别名并标记退役，新代码请使用
 *     {@link VersionedRetryRecordSerializer#INSTANCE}。
 * @author team4u
 */
@Deprecated
public class JsonRetryRecordSerializer implements RetryRecordSerializer {

    @Deprecated
    public static final JsonRetryRecordSerializer INSTANCE = new JsonRetryRecordSerializer();

    /**
     * 版本化实现的单例（委托目标）
     */
    private final VersionedRetryRecordSerializer delegate = VersionedRetryRecordSerializer.INSTANCE;

    @Deprecated
    public JsonRetryRecordSerializer() {
    }

    @Override
    public String serialize(RetryRecord record) {
        return delegate.serialize(record);
    }

    @Override
    public RetryRecord deserialize(String data) {
        return delegate.deserialize(data);
    }
}
