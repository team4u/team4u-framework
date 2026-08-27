package com.team4u.framework.kv.retryable;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.retry.api.Retries;
import com.team4u.framework.retry.api.RetryPolicy;

import java.util.Objects;

/**
 * 可重试键值存储装饰器：存储抖动（网络闪断、连接池耗尽）时按策略自动重试
 * <p>
 * 直接复用 {@code team4u-retry} 的 INLINE 模式（{@link Retries#inline()}），
 * 重试策略完全开放（退避、异常白名单、上限）。默认策略仅在
 * {@link KvStoreException} 上重试——业务语义失败（IF_ABSENT 冲突等）
 * 不属于基础设施故障，不会触发重试。
 * </p>
 * <pre>{@code
 * KvStore kv = new RetryableStore(delegate, RetryPolicy.builder()
 *         .maxRetries(3)
 *         .backoff(Backoffs.exponentialJitter(100, 2.0, 5000))
 *         .retryOn(KvStoreException.class)
 *         .build());
 * }</pre>
 *
 * @author jay.wu
 */
public class RetryableStore implements KvStore {

    private final KvStore delegate;
    private final RetryPolicy policy;

    public RetryableStore(KvStore delegate) {
        this(delegate, defaultPolicy());
    }

    public RetryableStore(KvStore delegate, RetryPolicy policy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * 默认策略：最多重试 2 次，指数退避（100ms 起，2 倍，上限 5s），仅重试基础设施异常
     */
    public static RetryPolicy defaultPolicy() {
        return RetryPolicy.builder()
                .maxRetries(2)
                .backoff(com.team4u.framework.retry.common.backoff.Backoffs
                        .exponentialJitter(100, 2.0, 5000))
                .retryOn(KvStoreException.class)
                .build();
    }

    @Override
    public KvRecord get(SpaceKey key) {
        return call("get", () -> delegate.get(key));
    }

    @Override
    public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
        return call("put", () -> delegate.put(key, record, mode));
    }

    @Override
    public boolean remove(SpaceKey key) {
        return call("remove", () -> delegate.remove(key));
    }

    @Override
    public boolean expire(SpaceKey key, long ttlMillis) {
        return call("expire", () -> delegate.expire(key, ttlMillis));
    }

    private <T> T call(String op, Operation<T> operation) {
        try {
            return Retries.inline().policy(policy).call(operation::execute);
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new KvStoreException("Retryable operation finally failed|op=" + op, e);
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T execute() throws Exception;
    }
}
