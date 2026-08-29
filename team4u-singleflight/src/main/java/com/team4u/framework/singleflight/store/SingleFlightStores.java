package com.team4u.framework.singleflight.store;

import com.team4u.framework.kv.KvStore;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;

/**
 * Named KvStore registry used by singleflight rules.
 *
 * @author jay.wu
 */
public class SingleFlightStores {

    private static final SingleFlightStores GLOBAL = new SingleFlightStores();

    private final KeyedPolicyRegistry<String, NamedStore> registry =
            new KeyedPolicyRegistry<>(NamedStore.class);

    public static SingleFlightStores global() {
        return GLOBAL;
    }

    public SingleFlightStores register(String name, KvStore store) {
        registry.register(new NamedStore(name, store));
        return this;
    }

    public KvStore resolve(String name) {
        return registry.get(name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Singleflight store not registered: " + name))
                .getStore();
    }
}
