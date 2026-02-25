package com.team4u.framework.config.core;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONUtil;
import com.team4u.framework.config.core.annotation.ConfigConverter;
import com.team4u.framework.config.core.convert.JsonPropertyConverter;
import com.team4u.framework.config.core.convert.PropertyConverter;
import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.spi.ConfigSource;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义转换器单元测试
 *
 * @author jay.wu
 */
public class ConfigConverterTest {

    @Test
    public void testConverters() {
        Map<String, ConfigEntry> entries = new HashMap<>();
        long now = System.currentTimeMillis();

        // CSV To List
        entries.put("app.whiteList", new ConfigEntry("app.whiteList", "a,b,c", "mock", now));
        // JSON To Bean
        entries.put("app.adminUser", new ConfigEntry("app.adminUser", "{\"name\":\"jay\",\"age\":18}", "mock", now));
        // Decrypt
        String encrypted = SecureUtil.aes("1234567812345678".getBytes()).encryptHex("secret_pwd");
        entries.put("app.password", new ConfigEntry("app.password", encrypted, "mock", now));

        ConfigSnapshot snapshot = new ConfigSnapshot(1L, entries);
        ConfigManager manager = ConfigManager.builder()
                .addSource(new StaticConfigSource(snapshot))
                .build();

        ConverterConfig config = manager.createProxy("app", ConverterConfig.class);

        // 验证 CSV 转换
        List<String> list = config.whiteList();
        Assert.assertEquals(3, list.size());
        Assert.assertEquals("a", list.get(0));

        // 验证 JSON 转换
        User admin = config.adminUser();
        Assert.assertEquals("jay", admin.getName());
        Assert.assertEquals(18, admin.getAge());

        // 验证解密转换
        Assert.assertEquals("secret_pwd", config.password());
    }

    public interface ConverterConfig {

        @ConfigConverter(CsvToListConverter.class)
        List<String> whiteList();

        @ConfigConverter(JsonPropertyConverter.class)
        User adminUser();

        @ConfigConverter(DecryptConverter.class)
        String password();
    }

    /**
     * CSV 转 List 转换器
     */
    public static class CsvToListConverter implements PropertyConverter<List<String>> {
        @Override
        public List<String> convert(String source, Class<List<String>> targetType) {
            return StrUtil.split(source, ',');
        }
    }

    /**
     * JSON 转 User 转换器
     */
    public static class UserInfoConverter implements PropertyConverter<User> {
        @Override
        public User convert(String source, Class<User> targetType) {
            return JSONUtil.toBean(source, targetType);
        }
    }

    /**
     * 解密转换器
     */
    public static class DecryptConverter implements PropertyConverter<String> {
        @Override
        public String convert(String source, Class<String> targetType) {
            return SecureUtil.aes("1234567812345678".getBytes()).decryptStr(source);
        }
    }

    public static class User {
        private String name;
        private int age;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }

    private static class StaticConfigSource implements ConfigSource {
        private final ConfigSnapshot snapshot;

        public StaticConfigSource(ConfigSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public String name() {
            return "static";
        }

        @Override
        public Map<String, ConfigEntry> load() {
            return snapshot.getEntries();
        }
    }
}
