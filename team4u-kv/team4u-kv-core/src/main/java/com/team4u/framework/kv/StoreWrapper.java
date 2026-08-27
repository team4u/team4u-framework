package com.team4u.framework.kv;

/**
 * 装饰器包装契约：暴露被包装的内层存储
 * <p>
 * 装饰器（TieredStore、ObservedStore、RetryableStore 等）实现本接口，
 * 使 {@link KvStores} 能沿装饰器链解析出真正实现能力接口的底层存储——
 * 「锁管理器拿到分层装饰过的存储」这类组合因此可直接工作，无需调用方手工拆包。
 * </p>
 *
 * @author jay.wu
 */
public interface StoreWrapper {

    /**
     * @return 被包装的内层存储（可能是另一个装饰器）
     */
    KvStore unwrap();
}
