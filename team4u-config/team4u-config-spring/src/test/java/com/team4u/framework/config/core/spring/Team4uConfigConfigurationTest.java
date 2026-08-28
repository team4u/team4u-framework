package com.team4u.framework.config.core.spring;

import com.team4u.framework.config.core.ConfigBootstrap;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.spi.ConfigSource;
import com.team4u.framework.config.core.spi.ConfigSourceRegistry;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Collections;
import java.util.Map;

import java.security.CodeSource;
public class Team4uConfigConfigurationTest {

    @After
    public void resetGlobalConfig() {
        ConfigBootstrap.global().resetForTests();
    }

    @Test
    public void explicitImportRegistersGlobalBeansAndRefreshesConfiguration() {
        ConfigBootstrap.global().resetForTests();
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                ApplicationConfiguration.class);
        try {
            ConfigManager manager = context.getBean(ConfigManager.class);

            Assert.assertSame(ConfigManager.global(), manager);
            Assert.assertEquals("team4u", manager.getString("spring.test.key").orElse(null));
            Assert.assertSame(ConfigSourceRegistry.global(),
                    context.getBean(ConfigSourceRegistry.class));
        } finally {
            context.close();
            ConfigBootstrap.global().resetForTests();
        }
    }

    @Test
    public void springFactoriesMetadataAndAutoConfigurationClassAreAbsent() throws Exception {
        CodeSource source = Team4uConfigConfiguration.class.getProtectionDomain().getCodeSource();
        String location = source.getLocation().toExternalForm();
        Assert.assertNull(getClass().getClassLoader().getResource(
                jarResourcePath(location, "META-INF/spring.factories")));
        try {
            Class.forName("com.team4u.framework.config.core.spring.Team4uConfigAutoConfiguration");
            Assert.fail("old auto-configuration class must not be published");
        } catch (ClassNotFoundException expected) {
            Assert.assertEquals(
                    "com.team4u.framework.config.core.spring.Team4uConfigAutoConfiguration",
                    expected.getMessage());
        }
    }

    private static String jarResourcePath(String codeSourceLocation, String resource) {
        Assert.assertTrue("config-spring test must run from its module classes: " + codeSourceLocation,
                codeSourceLocation.endsWith("/target/classes/"));
        return codeSourceLocation.substring(codeSourceLocation.indexOf("!/") + 2) + resource;
    }

    @Configuration
    @Import(Team4uConfigConfiguration.class)
    static class ApplicationConfiguration {

        @Bean
        public ConfigSource testConfigSource() {
            return new ConfigSource() {
                @Override
                public Map<String, ConfigEntry> load() {
                    return Collections.singletonMap("spring.test.key",
                            new ConfigEntry("spring.test.key", "team4u", name(), priority()));
                }

                @Override
                public String name() {
                    return "configuration-test";
                }
            };
        }
    }
}
