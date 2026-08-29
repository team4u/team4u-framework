package com.team4u.framework.lease.spi;

import com.team4u.framework.base.util.IdUtil;

/**
 * 租约持有者令牌（lease token）生成器
 * <p>
 * 令牌是租约的 fencing 凭证：每次成功抢占（CAS）都会签发一个新令牌，
 * 写入 lease_token 字段并随 LeaseHandle 交给持有者。后续的 heartbeat/close/release
 * 均以令牌匹配为前提，令牌不匹配即视为租约已被他人接管（RuntimeResult.LEASE_LOST），
 * 从而阻止旧持有者对已易主的任务写入。
 * </p>
 * <p>
 * 此前 JdbcLeaseBackend 用 {@code IdUtil.simpleUUID()}、InMemoryLeaseBackend 用
 * {@code UUID.randomUUID().toString()} 各自生成，本类统一为单一实现，
 * 保证两种后端签发的令牌格式与随机性一致（32 位无连字符 UUID）。
 * </p>
 *
 * @author jay.wu
 */
public final class LeaseTokens {

    private LeaseTokens() {
    }

    /**
     * 生成一个新的租约持有者令牌
     * <p>
     * 格式为移除连字符的 32 位 UUID，全局唯一且不可预测，
     * 满足「同一任务先后两次抢占必然拿到不同令牌」的 fencing 要求。
     *
     * @return 新令牌
     */
    public static String nextToken() {
        return IdUtil.simpleUUID();
    }
}
