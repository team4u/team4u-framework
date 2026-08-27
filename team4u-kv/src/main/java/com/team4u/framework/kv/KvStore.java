package com.team4u.framework.kv;

/**
 * 键值存储核心抽象
 * <p>
 * 刻意保持最小接口，只包含四类原子操作；扫描、批量、订阅等能力
 * 由上层按需以可选能力接口或装饰器形式扩展（如 {@code TieredStore}、{@code HotSwapStore}）。
 * </p>
 * 过期语义统一由 {@link KvRecord} 携带：
 * <ul>
 *     <li>不支持原生过期的实现（如内存、数据库）在读取时惰性判定</li>
 *     <li>支持原生过期的实现（如 Redis）由存储自身淘汰</li>
 * </ul>
 */
public interface KvStore {

    /**
     * 读取记录
     *
     * @return 记录；键不存在或已过期返回 {@code null}
     */
    KvRecord get(SpaceKey key);

    /**
     * 写入记录
     *
     * @param mode 写入模式，见 {@link PutMode}
     * @return {@code true} 写入成功；{@code false} 表示 IF_ABSENT 模式下键已存在
     */
    boolean put(SpaceKey key, KvRecord record, PutMode mode);

    /**
     * 删除记录
     *
     * @return {@code true} 表示删除了存在且未过期的记录
     */
    boolean remove(SpaceKey key);

    /**
     * 为已存在的记录设置新的有效期（值保持不变）
     *
     * @param ttlMillis 新的有效时长（毫秒），小于等于 0 视为永不过期
     * @return {@code true} 表示记录存在且续期成功
     */
    boolean expire(SpaceKey key, long ttlMillis);
}
