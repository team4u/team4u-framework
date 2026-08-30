package com.team4u.framework.config.core.support;

import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import lombok.Data;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON 配置仓库模板单元测试
 * <p>
 * 覆盖统一降级语义：首次加载失败快速失败、热更新失败保留旧配置、空配置回退缺省值。
 */
public class AbstractJsonConfigRepositoryTest {

    private InMemoryConfigSource configSource;
    private ConfigManager configManager;

    @Before
    public void setUp() {
        configSource = new InMemoryConfigSource("test", 100);
        configManager = ConfigManager.builder()
                .addSource(configSource)
                .addWatcher(configSource)
                .debounceWindow(0)
                .build();
    }

    @After
    public void tearDown() {
        if (configManager instanceof AutoCloseable) {
            try {
                ((AutoCloseable) configManager).close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void testInitLoadsExistingConfig() {
        configSource.putAndRefresh("test.repo.simple", "{\"name\":\"foo\",\"count\":3}");

        SimpleRepository repository = new SimpleRepository();
        repository.init(configManager);

        Assert.assertTrue(repository.isInitialized());
        Assert.assertEquals("foo", repository.get().getName());
        Assert.assertEquals(3, repository.get().getCount());
    }

    @Test
    public void testEmptyConfigFallsBackToDefault() {
        SimpleRepository repository = new SimpleRepository();
        repository.init(configManager);

        Assert.assertNotNull(repository.get());
        Assert.assertEquals("default", repository.get().getName());
    }

    @Test(expected = IllegalStateException.class)
    public void testFirstLoadFailureFailsFast() {
        configSource.putAndRefresh("test.repo.simple", "{");
        SimpleRepository repository = new SimpleRepository();
        repository.init(configManager);
    }

    @Test(expected = IllegalStateException.class)
    public void testMissingTypeReferenceFailsFast() {
        configSource.putAndRefresh("test.repo.plain", "anything");
        PlainRepository repository = new PlainRepository();
        repository.init(configManager);
    }

    @Test
    public void testHotReloadReplacesConfigAtomically() throws Exception {
        configSource.putAndRefresh("test.repo.simple", "{\"name\":\"v1\"}");
        SimpleRepository repository = new SimpleRepository();
        repository.init(configManager);
        Assert.assertEquals("v1", repository.get().getName());

        configSource.putAndRefresh("test.repo.simple", "{\"name\":\"v2\"}");
        Thread.sleep(50);

        Assert.assertEquals("v2", repository.get().getName());
        Assert.assertEquals(2, repository.loadCount.get());
    }

    @Test
    public void testHotReloadFailureKeepsOldConfig() throws Exception {
        configSource.putAndRefresh("test.repo.simple", "{\"name\":\"v1\"}");
        SimpleRepository repository = new SimpleRepository();
        repository.init(configManager);
        Assert.assertEquals("v1", repository.get().getName());

        // 推送非法 JSON：热更新失败应保留旧配置
        configSource.putAndRefresh("test.repo.simple", "{");
        Thread.sleep(50);

        Assert.assertEquals("v1", repository.get().getName());
    }

    @Test
    public void testConfigDeleteFallsBackToEmptyConfig() throws Exception {
        configSource.putAndRefresh("test.repo.simple", "{\"name\":\"v1\"}");
        SimpleRepository repository = new SimpleRepository();
        repository.init(configManager);

        configSource.delete("test.repo.simple");
        configSource.fireChange();
        Thread.sleep(50);

        Assert.assertEquals("default", repository.get().getName());
    }

    @Test
    public void testParseJsonOverrideWithCustomLogic() {
        configSource.putAndRefresh("test.repo.custom", "{\"name\":\"raw\"}");
        CustomParseRepository repository = new CustomParseRepository();
        repository.init(configManager);

        Assert.assertEquals("raw-parsed", repository.get().getName());
    }

    @Test
    public void testStopReleasesListenerAndResetsConfig() throws Exception {
        configSource.putAndRefresh("test.repo.simple", "{\"name\":\"v1\"}");
        SimpleRepository repository = new SimpleRepository();
        repository.init(configManager);

        repository.stop();

        Assert.assertFalse(repository.isInitialized());
        // stop 后回退缺省配置
        Assert.assertEquals("default", repository.get().getName());

        // stop 后配置变更不再触发回调
        int countAfterStop = repository.loadCount.get();
        configSource.putAndRefresh("test.repo.simple", "{\"name\":\"v3\"}");
        Thread.sleep(50);
        Assert.assertEquals(countAfterStop, repository.loadCount.get());
    }

    @Test
    public void testReinitSwitchesConfigManager() throws Exception {
        InMemoryConfigSource anotherSource = new InMemoryConfigSource("another", 100);
        ConfigManager anotherManager = ConfigManager.builder()
                .addSource(anotherSource)
                .addWatcher(anotherSource)
                .debounceWindow(0)
                .build();
        try {
            configSource.putAndRefresh("test.repo.simple", "{\"name\":\"first\"}");
            anotherSource.putAndRefresh("test.repo.simple", "{\"name\":\"second\"}");

            SimpleRepository repository = new SimpleRepository();
            repository.init(configManager);
            Assert.assertEquals("first", repository.get().getName());

            repository.init(anotherManager);
            Assert.assertEquals("second", repository.get().getName());

            // 旧上下文的变更不再影响仓库
            configSource.putAndRefresh("test.repo.simple", "{\"name\":\"first-updated\"}");
            Thread.sleep(50);
            Assert.assertEquals("second", repository.get().getName());

            repository.stop();
        } finally {
            if (anotherManager instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) anotherManager).close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Data
    public static class SampleConfig {
        private String name = "default";
        private int count;

        public static SampleConfig defaults() {
            return new SampleConfig();
        }
    }

    private static class SimpleRepository extends AbstractJsonConfigRepository<SampleConfig> {
        final AtomicInteger loadCount = new AtomicInteger();

        @Override
        protected String configKey() {
            return "test.repo.simple";
        }

        @Override
        protected TypeReference<SampleConfig> typeReference() {
            return new TypeReference<SampleConfig>() {
            };
        }

        @Override
        protected SampleConfig emptyConfig() {
            return SampleConfig.defaults();
        }

        @Override
        protected void onConfigLoaded(SampleConfig oldValue, SampleConfig newValue) {
            loadCount.incrementAndGet();
        }
    }

    private static class PlainRepository extends AbstractJsonConfigRepository<Map<String, Object>> {
        @Override
        protected String configKey() {
            return "test.repo.plain";
        }
    }

    private static class CustomParseRepository extends AbstractJsonConfigRepository<SampleConfig> {
        @Override
        protected String configKey() {
            return "test.repo.custom";
        }

        @Override
        protected SampleConfig parseJson(String json) throws Exception {
            SampleConfig parsed = com.team4u.framework.serializer.json.JsonUtil.toBean(json, SampleConfig.class);
            parsed.setName(parsed.getName() + "-parsed");
            return parsed;
        }
    }
}
