package com.team4u.it;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.internal.DefaultConfigBinder;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;

public class ConfigCoreMain {

    public static void main(String[] args) {
        InMemoryConfigSource source = new InMemoryConfigSource("consumer", 100);
        source.put("app.name", "Team4u");
        source.put("app.port", "8080");

        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .build();

        if (!"Team4u".equals(manager.getString("app.name").orElse(null))
                || !"8080".equals(manager.getString("app.port").orElse(null))) {
            throw new IllegalStateException("Config-core scalar reads failed");
        }

        ApplicationConfig config = new DefaultConfigBinder()
                .bind(manager.currentSnapshot(), "app", ApplicationConfig.class);
        if (config == null || !"Team4u".equals(config.getName()) || config.getPort() != 8080) {
            throw new IllegalStateException("Config-core explicit binding failed: " + config);
        }
        System.out.println(config.getName() + ":" + config.getPort());
    }

    public static class ApplicationConfig {
        private String name;
        private int port;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }
}
