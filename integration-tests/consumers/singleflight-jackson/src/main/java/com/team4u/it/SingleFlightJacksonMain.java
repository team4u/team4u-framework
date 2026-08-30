package com.team4u.it;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.serializer.json.JsonUtil;
import com.team4u.framework.singleflight.api.SingleFlightExecution;
import com.team4u.framework.singleflight.api.SingleFlights;

import java.util.Collections;
import java.util.Map;

/**
 * External consumer proof for the split singleflight core artifact:
 * BOM import + team4u-singleflight-core + the application-owned explicit
 * provider team4u-serializer-jackson run a real single-flight execution
 * (single loader execution, cache hit for the follower) without any proxy or
 * Spring dependency.
 */
public class SingleFlightJacksonMain {

    public static void main(String[] args) {
        // 1. One JSON rule: merge on productId, cache the result for 60s.
        InMemoryConfigSource source = new InMemoryConfigSource("consumer", 0);
        source.put("team4u.singleflight.product.detail",
                "{\"id\":\"product.detail\",\"key\":\"${productId}\","
                        + "\"cacheTtlMillis\":60000}");
        ConfigManager configManager = ConfigManager.builder()
                .addSource(source).addWatcher(source).build();

        SingleFlights.init(configManager, new InMemoryKvStore());
        try {
            Map<String, Object> arguments = Collections.singletonMap("productId", "p1");

            // 2. First call executes the real loader.
            String first = SingleFlights.execute(SingleFlightExecution.of(
                    "product.detail", arguments, String.class,
                    (SingleFlightExecution.SingleFlightLoader<String>) () -> "product:p1"));

            // 3. Second call hits the session cache; its loader must not run.
            String second = SingleFlights.execute(SingleFlightExecution.of(
                    "product.detail", arguments, String.class,
                    (SingleFlightExecution.SingleFlightLoader<String>) () -> {
                        throw new IllegalStateException("cache hit must not run the loader");
                    }));

            if (!"product:p1".equals(first) || !"product:p1".equals(second)) {
                throw new IllegalStateException("Singleflight results wrong: " + first + "/" + second);
            }

            // 4. The explicit provider is application-owned: JsonUtil works at runtime.
            Payload roundtrip = JsonUtil.toBean(
                    JsonUtil.toJsonStr(new Payload("Team4u", 1)), Payload.class);
            if (roundtrip == null || !"Team4u".equals(roundtrip.getName()) || roundtrip.getVersion() != 1) {
                throw new IllegalStateException("Provider roundtrip failed: " + roundtrip);
            }

            System.out.println("singleflight-jackson consumer ok: " + first + "/" + second
                    + " provider=" + roundtrip.getName());
        } finally {
            SingleFlights.destroy();
        }
    }

    public static class Payload {
        private String name;
        private int version;

        public Payload() {
        }

        public Payload(String name, int version) {
            this.name = name;
            this.version = version;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }
    }
}
