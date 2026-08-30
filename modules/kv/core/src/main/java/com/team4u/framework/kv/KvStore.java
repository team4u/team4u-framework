package com.team4u.framework.kv;

/**
 * 键值存储核心抽象
 * <p>
 * 刻意保持最小接口，只包含四类原子操作；原子比较替换、扫描、批量、订阅等能力
 * 由可选能力接口扩展（见 {@link CasCapable}、{@link ScanCapable}、{@link WatchCapable}、
 * {@link NativeTtlCapable}），装饰器（TieredStore、ObservedStore 等）提供横切组合。
 * </p>
 *
 * <h3>实现契约</h3>
 * <ul>
 *     <li><b>异常</b>：基础设施故障（连接失败、序列化失败等）抛出
 *     {@link KvStoreException} 等非受检异常；「键不存在或已过期」不是异常，
 *     以 {@code null}/{@code false} 表达。调用方可据此区分“无数据”与“存储不可用”</li>
 *     <li><b>过期精度</b>：{@link #get} 返回的 {@link KvRecord#getExpireAt()}
 *     必须精确到 epoch 毫秒（0 仅表示永不过期）。这是跨实现的行为一致性契约，
 *     分层存储等上层组件依赖它做过期兜底判定；原生 TTL 存储（如 Redis）需以
 *     剩余 TTL 换算，不得图省事返回 0</li>
 *     <li><b>值域</b>：值刻意限定为 {@link String}（JSON 等文本负载）。
 *     二进制负载规划由字节值域能力接口扩展，避免现在引入编码歧义</li>
 *     <li><b>原子性</b>：{@link #put} 的 IF_ABSENT 模式必须原子（对应 Redis
 *     SETNX、数据库唯一索引）；不支持的实现应在构造期或调用期快速失败</li>
 *     <li><b>过期语义</b>：由 {@link KvRecord} 自身携带——不支持原生过期的实现
 *     （内存、数据库）在读取时惰性判定；支持原生过期的实现（Redis）由存储自身淘汰</li>
 *     <li><b>expire 的 ttl 语义</b>：{@code ttlMillis <= 0} 表示改为永不过期
 *     （对应 Redis PERSIST 语义），与 Redis 原生“负 TTL 即删除”不同，
 *     实现者需注意映射</li>
 * </ul>
 *
 * @author jay.wu
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
     * @return {@code true} 表示记录存在且续期成功；{@code false} 表示未续期成功
     * （键不存在、已过期，或与并发写入竞争失败——此时键可能仍然存在）
     */
    boolean expire(SpaceKey key, long ttlMillis);
}
