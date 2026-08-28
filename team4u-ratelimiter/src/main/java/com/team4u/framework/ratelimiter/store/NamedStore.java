package com.team4u.framework.ratelimiter.store;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.policy.api.KeyedPolicy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 命名存储：限流规则与具体存储的绑定载体
 *
 * @author jay.wu
 */
@Getter
@RequiredArgsConstructor
public class NamedStore implements KeyedPolicy<String> {

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
