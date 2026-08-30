package com.team4u.framework.kv;

/**
 * 原生 TTL 能力标记
 * <p>
 * 实现本接口（标记接口）表示存储自身支持过期淘汰（如 Redis），
 * 清理器应跳过该存储，无需周期性 {@code pruneExpired}。
 * </p>
 *
 * @author jay.wu
 */
public interface NativeTtlCapable {
}
