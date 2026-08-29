package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.retry.common.backoff.BackoffRegistry;
import com.team4u.framework.retry.managed.store.record.RetryRecord;
import com.team4u.framework.retry.managed.store.serialize.RetryRecordSerializer;
import com.team4u.framework.retry.managed.store.serialize.VersionedRetryRecordSerializer;

import java.util.Set;

/**
 * lease 持久化场景的重试记录序列化器。
 * <p>
 * 版本化 JSON 映射（schema v1：显式字段、退避按 {@code type + params} 重建、
 * 异常类名 allowlist、终态校验）已下沉至 retry-core 的
 * {@link VersionedRetryRecordSerializer}，作为 {@link RetryRecordSerializer}
 * 的默认可靠实现；本类保留为 lease 模块的兼容入口，纯委托无自有逻辑。
 * <p>
 * 保留原因：{@code LeaseDurableRetryStore}/{@code RetryTaskWorker}/
 * {@code ManagedRetryRuntime} 及既有用户代码以本类名为默认序列化器装配点。
 *
 * @author team4u
 */
public final class LeaseRetryRecordSerializer implements RetryRecordSerializer {

    /**
     * @deprecated schema 版本常量已随实现下沉，请直接使用
     *     {@link VersionedRetryRecordSerializer#SCHEMA_VERSION}
     */
    @Deprecated
    public static final int SCHEMA_VERSION = VersionedRetryRecordSerializer.SCHEMA_VERSION;

    public static final LeaseRetryRecordSerializer INSTANCE =
            new LeaseRetryRecordSerializer(BackoffRegistry.global());

    private final VersionedRetryRecordSerializer delegate;

    public LeaseRetryRecordSerializer() {
        this(new VersionedRetryRecordSerializer());
    }

    public LeaseRetryRecordSerializer(BackoffRegistry backoffRegistry) {
        this(new VersionedRetryRecordSerializer(backoffRegistry));
    }

    public LeaseRetryRecordSerializer(
            BackoffRegistry backoffRegistry, Set<Class<? extends Throwable>> throwableAllowlist) {
        this(new VersionedRetryRecordSerializer(backoffRegistry, throwableAllowlist));
    }

    private LeaseRetryRecordSerializer(VersionedRetryRecordSerializer delegate) {
        this.delegate = delegate;
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
