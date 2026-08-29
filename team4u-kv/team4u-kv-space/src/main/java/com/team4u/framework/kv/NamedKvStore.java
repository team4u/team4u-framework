package com.team4u.framework.kv;

import com.team4u.framework.policy.api.KeyedPolicy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 命名存储：存储名与 {@link KvStore} 实例的绑定载体
 * <p>
 * 作为 {@link com.team4u.framework.policy.core.KeyedPolicyRegistry} 的注册单元，
 * 由 {@link NamedKvStoreRegistry} 统一管理。规则侧（限流、回源合并、序号等）
 * 以 {@code store} 字段按名引用存储，实现「一套规则、多存储分工」。
 * </p>
 *
 * @author jay.wu
 */
@Getter
@RequiredArgsConstructor
public class NamedKvStore implements KeyedPolicy<String> {

    /**
     * 存储名，即规则中的 {@code store} 配置
     */
    private final String name;

    /**
     * 绑定的键值存储
     */
    private final KvStore store;

    @Override
    public String key() {
        return name;
    }
}
