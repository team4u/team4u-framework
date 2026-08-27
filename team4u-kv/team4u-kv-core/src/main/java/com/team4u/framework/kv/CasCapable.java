package com.team4u.framework.kv;

/**
 * 原子比较替换能力
 * <p>
 * 基于存储值精确匹配（字符串相等）的 CAS 语义，是 fencing 安全的锁、
 * 所有权安全的续期/释放（{@code team4u-kv-lock}）的基础。
 * 无法保证原子性的存储不应实现本接口，锁会在构造期快速失败。
 * </p>
 *
 * @author jay.wu
 */
public interface CasCapable {

    /**
     * 仅当存储中的存活记录值与 {@code expectedValue} 精确相等时，替换为新记录
     *
     * @return {@code true} 替换成功；键不存在、已过期或值不匹配返回 {@code false}
     */
    boolean compareAndSet(SpaceKey key, String expectedValue, KvRecord update);

    /**
     * 仅当存储中的存活记录值与 {@code expectedValue} 精确相等时删除
     *
     * @return {@code true} 删除成功；键不存在、已过期或值不匹配返回 {@code false}
     */
    boolean compareAndRemove(SpaceKey key, String expectedValue);
}
