package com.team4u.framework.config.core;

import com.team4u.framework.config.core.annotation.ConfigConverter;
import com.team4u.framework.config.core.convert.PropertyConverter;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.proxy.ConfigProxyFactory;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import org.junit.Assert;
import org.junit.Test;

public class ConfigProxyFactoryContextTest {

    @Test
    public void creatorBridgeUsesConverterFromConfigProxyContext() {
        InMemoryConfigSource source = new InMemoryConfigSource("factory-context", 1);
        source.put("app.marker", "raw");
        PropertyConverterRegistry capturedRegistry = new PropertyConverterRegistry();
        capturedRegistry.register(new MarkerConverter("captured"));
        ConfigProxyFactory capturedFactory = new ConfigProxyFactory(capturedRegistry);

        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .addConverter(new MarkerConverter("context"))
                .proxyCreator(capturedFactory)
                .build();

        MarkerConfig proxy = manager.createProxy("app", MarkerConfig.class);

        Assert.assertEquals("context", proxy.getMarker());
    }


    public static class MarkerConfig {
        @ConfigConverter(MarkerConverter.class)
        private String marker;

        public String getMarker() {
            return marker;
        }
    }

    public static class MarkerConverter implements PropertyConverter<String> {
        private final String value;

        public MarkerConverter(String value) {
            this.value = value;
        }

        public MarkerConverter() {
            this("default");
        }

        @Override
        public String convert(String source, Class<String> targetType) {
            return value;
        }
    }
}
