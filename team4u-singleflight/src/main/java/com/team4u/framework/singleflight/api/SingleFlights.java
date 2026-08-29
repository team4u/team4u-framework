package com.team4u.framework.singleflight.api;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.singleflight.core.SingleFlightEngine;

import java.util.Objects;

/**
 * Static facade for the global engine.
 *
 * @author jay.wu
 */
public final class SingleFlights {

    private static volatile SingleFlightEngine engine;

    private SingleFlights() {
    }

    public static void init(ConfigManager configManager, KvStore store) {
        init(configManager, store, java.time.Clock.systemUTC());
    }

    public static synchronized void init(ConfigManager configManager, KvStore store,
                                         java.time.Clock clock) {
        Objects.requireNonNull(configManager, "configManager");
        Objects.requireNonNull(store, "store");
        destroy();
        engine = new SingleFlightEngine(configManager, store, clock);
    }

    public static <T> T execute(SingleFlightExecution<T> execution) {
        return engine().execute(execution);
    }

    public static synchronized void destroy() {
        SingleFlightEngine current = engine;
        if (current != null) {
            current.destroy();
            engine = null;
        }
    }

    private static SingleFlightEngine engine() {
        SingleFlightEngine current = engine;
        if (current != null) {
            return current;
        }
        synchronized (SingleFlights.class) {
            current = engine;
            if (current == null) {
                current = new SingleFlightEngine(ConfigManager.global(), new InMemoryKvStore());
                engine = current;
            }
            return current;
        }
    }
}
