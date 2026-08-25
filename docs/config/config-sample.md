# 实战案例与测试支持

本章介绍 `team4u-config` 在企业级微服务、Spring Boot 自动装配、单元测试环境以及全局生命周期锁定中的实战范例。

---

## 微服务多环境多源配置聚合

### 业务场景
微服务需适配本地研发、CI/CD 自动化测试与生产环境。要求：
1. 本地打包自带基础默认配置（`PropertiesConfigSource`，优先级 100，作为兜底）；
2. 远程关系型数据库集中下发业务动态配置（`DbConfigSource`，优先级 0，高优先级覆盖）；
3. 容器启动时通过环境变量或 JVM 参数（`SystemEnvConfigSource`，优先级 -100，最高优先级强行覆盖）；
4. 数据库配置变更时 5 秒内自动热生效。

### 代码实现
```java
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.PropertiesConfigSource;
import com.team4u.framework.config.core.spi.SystemEnvConfigSource;
import com.team4u.framework.config.db.DbConfigOptions;
import com.team4u.framework.config.db.DbConfigSource;
import com.team4u.framework.config.db.DbConfigWatcher;
import javax.sql.DataSource;
import java.io.IOException;

public class ApplicationConfigBootstrap {

    public static ConfigManager initConfigManager(DataSource prodDataSource) throws IOException {
        // 1. 本地 properties 文件（优先级 100，作为默认兜底配置）
        PropertiesConfigSource localSource = PropertiesConfigSource.fromResource(
                "Local-File", 
                100, 
                "config/app.properties"
        );

        // 2. 数据库配置表与监听器（优先级 0，用于运维动态调控）
        DbConfigOptions dbOptions = new DbConfigOptions().setTableName("system_config");
        DbConfigSource dbSource = new DbConfigSource("Prod-DB", 0, prodDataSource, dbOptions);
        DbConfigWatcher dbWatcher = new DbConfigWatcher(prodDataSource, 5, dbOptions);

        // 3. JVM 参数与环境变量（优先级 -100，最高优先级用于容器启动时覆盖）
        SystemEnvConfigSource envSource = new SystemEnvConfigSource("System-Env", -100);

        // 4. 组装构建全局配置中心
        return ConfigManager.builder()
                .addSource(envSource)
                .addSource(dbSource)
                .addSource(localSource)
                .addWatcher(dbWatcher)
                .debounceWindow(500) // 500ms 防抖
                .build();
    }
}
```

---

## Spring Boot 自动装配集成

`team4u-config-core` 内置了 `Team4uConfigAutoConfiguration`，可无缝融入 Spring 生态。

### 自动装配原理
```java
@Configuration
public class Team4uConfigAutoConfiguration {
    @Bean
    @PolicyAutoRegister
    public ConfigSourceRegistry globalSourceRegistry() {
        return ConfigSourceRegistry.global();
    }

    @Bean
    @PolicyAutoRegister
    public ConfigWatcherRegistry globalWatcherRegistry() {
        return ConfigWatcherRegistry.global();
    }

    @Bean
    @PolicyAutoRegister
    public PropertyConverterRegistry globalConverterRegistry() {
        return PropertyConverterRegistry.global();
    }

    @Bean
    public ConfigManager globalConfigManager() {
        return ConfigManager.global();
    }

    @Bean
    public ApplicationListener<ContextRefreshedEvent> configRefresher() {
        return event -> DefaultConfigManager.global().refresh();
    }
}
```

### 在业务 Spring Bean 中使用配置代理
```java
@Configuration
public class AppConfigDeclaration {

    @Bean
    public ServerConfig serverConfig(ConfigManager configManager) {
        // 创建并注册单例代理 Bean，实时响应配置热更新
        return configManager.createProxy(ServerConfig.class);
    }
}

@Service
public class OrderService {

    @Autowired
    private ServerConfig serverConfig;

    public void handleOrder() {
        int timeout = serverConfig.getConnectTimeout();
        // 实时获取最新配置
    }
}
```

---

## 单元测试环境支持 (`TestConfigContext`)

在编写单元测试时，频繁修改本地文件或连接真实数据库既不稳定又污染环境。`team4u-config-test` 模块提供了专用的 `TestConfigContext`。

### 核心特性
- **默认 0 延迟同步重载**：内置 `debounceWindow(0)`，写入配置后当前线程立即完成重载，彻底消除 `Thread.sleep` 等待。
- **内存完全隔离**：独立构建 `InMemoryConfigSource`，不污染全局单例。
- **完备的动态操作 API**：`put`、`delete`（Tombstone 失效）、`remove`（物理清除）。

### 单元测试代码示例
```java
import com.team4u.framework.config.test.TestConfigContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import lombok.Getter;

public class AppServiceUnitTest {

    private TestConfigContext context;
    private AppConfig config;

    @Getter
    public static class AppConfig {
        private String appName = "default-name";
        private int maxThreads = 10;
    }

    @Before
    public void setUp() {
        // 1. 创建隔离测试上下文
        context = TestConfigContext.create();
        
        // 2. 注入测试初始配置
        context.put("app.name", "unit-test-app")
               .put("app.maxThreads", "50");

        // 3. 创建强类型代理
        config = context.createProxy("app", AppConfig.class);
    }

    @After
    public void tearDown() {
        context.destroy();
    }

    @Test
    public void testDynamicConfigReload() {
        Assert.assertEquals("unit-test-app", config.getAppName());
        Assert.assertEquals(50, config.getMaxThreads());

        // 模拟运行时动态热更配置（同步立即生效，无需 Thread.sleep）
        context.put("app.maxThreads", "100");
        Assert.assertEquals(100, config.getMaxThreads());

        // 模拟删除配置 (Tombstone 失效，回退到字段初始默认值 10)
        context.delete("app.maxThreads");
        Assert.assertEquals(10, config.getMaxThreads());
    }
}
```

---

## 全局引导与锁定机制 (`ConfigBootstrap`)

为了规范全局配置组件的注册并防止运行期配置源被非法篡改，`ConfigBootstrap` 提供了注册与锁定支持：

```java
import com.team4u.framework.config.core.ConfigBootstrap;

public class AppBootstrapListener {

    public static void onAppStartup() {
        ConfigBootstrap bootstrap = ConfigBootstrap.global();

        // 1. 统一注册全局配置源与监听器
        bootstrap.addSource(new MyCustomConfigSource())
                 .addWatcher(new MyCustomConfigWatcher())
                 .addConverter(new MyCustomConverter());

        // 2. 在应用启动完成后锁定注册表
        bootstrap.lock();

        // 3. 锁定后若再次尝试注册，将立即抛出 IllegalStateException 阻止非法篡改
        // bootstrap.addSource(...); // 抛出异常: Config global registry is locked
    }
}
```
