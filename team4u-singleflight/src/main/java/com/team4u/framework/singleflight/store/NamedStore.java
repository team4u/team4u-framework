package com.team4u.framework.singleflight.store;

import com.team4u.framework.kv.KvStore;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
/**
 * 命名存储绑定：规则 {@code store} 字段的名字与具体 {@link KvStore} 实例的对应关系，
 * 作为 {@code KeyedPolicyRegistry} 的注册单元。
 *
 * @author jay.wu
 */
@Getter
@RequiredArgsConstructor
public class NamedStore implements com.team4u.framework.policy.api.KeyedPolicy<String> {

    private final String name;
    private final KvStore store;

    @Override
    public String key() {
        return name;
    }
}
