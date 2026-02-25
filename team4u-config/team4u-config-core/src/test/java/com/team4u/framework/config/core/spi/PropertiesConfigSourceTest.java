package com.team4u.framework.config.core.spi;

import com.team4u.framework.config.core.domain.ConfigEntry;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

/**
 * {@link PropertiesConfigSource} 单元测试
 */
public class PropertiesConfigSourceTest {

    @Test
    public void testLoadFromProperties() {
        Properties properties = new Properties();
        properties.setProperty("app.name", "team4u");
        properties.setProperty("app.version", "1.0.0");

        PropertiesConfigSource source = new PropertiesConfigSource("prop-test", 20, properties);

        Assert.assertEquals("prop-test", source.name());
        Assert.assertEquals(20, source.priority());

        Map<String, ConfigEntry> result = source.load();
        Assert.assertEquals(2, result.size());
        Assert.assertEquals("team4u", result.get("app.name").getValue());
        Assert.assertEquals("1.0.0", result.get("app.version").getValue());
        Assert.assertEquals("prop-test", result.get("app.name").getSourceName());
    }

    @Test
    public void testLoadFromResource() throws IOException {
        PropertiesConfigSource source = PropertiesConfigSource.fromResource("res-test", 15, "test.properties");

        Assert.assertEquals("res-test", source.name());
        Assert.assertEquals(15, source.priority());

        Map<String, ConfigEntry> result = source.load();
        Assert.assertEquals(2, result.size());
        Assert.assertEquals("test.value", result.get("test.key").getValue());
        Assert.assertEquals("123", result.get("test.number").getValue());
    }

    @Test(expected = IOException.class)
    public void testLoadFromResourceNotFound() throws IOException {
        PropertiesConfigSource.fromResource("fail", 0, "not-exist.properties");
    }

    @Test
    public void testLoadWithNullProperties() {
        PropertiesConfigSource source = new PropertiesConfigSource("null-test", 30, null);
        Assert.assertTrue(source.load().isEmpty());
    }
}
