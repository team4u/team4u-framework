package com.team4u.framework.singleflight.store;

import com.team4u.framework.kv.KvStore;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
/**
 * Binding between a rule's {@code store} name and a KvStore.
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
