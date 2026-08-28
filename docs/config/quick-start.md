# 快速开始

本文介绍如何在 3 分钟内快速使用 `team4u-config`。

---

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-config-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

本文的键值读取和显式绑定不引入 Spring。强类型代理是可选能力；在 `team4u-config-proxy` 发布前，代理实现仍临时保留在 core 中。拆分完成后需要额外引入：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-config-proxy</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`JsonPropertyConverter` 的 JSON 路径仍按模块文档单独选择 JSON 引擎。在 `team4u-config-proxy` 发布前，`ConfigManager.builder().proxyCreator(...)` 是唯一启用代理的方式；未提供创建器时，`createProxy` 会快速失败，不会退回绑定 POJO。`team4u-config-test` 的 `TestConfigContext` 已在内部注入当前过渡实现。

若需使用关系型数据库（MySQL/PostgreSQL/H2 等）作为配置源，额外引入：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-config-db</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## 准备配置文件

在 `src/main/resources/test.properties`（或通过 `PropertiesConfigSource.fromResource` 加载的资源文件）中添加配置项：

```properties
server.name=team4u-demo
server.port=8080
server.connect-timeout=5000
server.db.url=jdbc:mysql://localhost:3306/test
server.db.username=root
# 占位符引用
server.description=${server.name} is running on port ${server.port}
```

---

## 基础键值读取

`ConfigManager` 提供了直接获取字符串配置的快捷方法。该路径不创建强类型代理，也不依赖 Jackson、ByteBuddy 或 Spring：

```java
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.base.convert.ConvertUtil;
import java.util.Optional;

public class ConfigQuickStart {

    public static void main(String[] args) {
        ConfigManager manager = ConfigManager.global();

        // 1. 读取字符串（返回 Optional 防 NPE）
        String serverName = manager.getString("server.name").orElse("default-app");

        // 2. 读取并通过 ConvertUtil 转换为目标类型
        int port = manager.getString("server.port")
                          .map(v -> ConvertUtil.convert(int.class, v))
                          .orElse(8080);

        // 3. 读取解析后的复合占位符配置
        String desc = manager.getString("server.description").orElse("");
        System.out.println(desc); // 输出: team4u-demo is running on port 8080
    }
}
```

---
## 固定快照显式绑定

`DefaultConfigBinder` 仍保留给需要一次性固定配置对象的场景。它不创建实时代理，也不会作为 `createProxy` 的降级实现：

```java
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.internal.DefaultConfigBinder;

ConfigManager manager = ConfigManager.builder()
        .addSource(source)
        .build();
AppConfig bound = new DefaultConfigBinder()
        .bind(manager.currentSnapshot(), "server", AppConfig.class);
```

---

## 推荐用法：强类型 JavaBean 动态代理

如果只需要固定值，显式绑定已经足够；需要随热更新实时读取时再使用代理。

### 定义普通的 JavaBean 配置类

只需提供无参构造函数与 Getter 方法，字段的初始值将自动作为配置缺失时的兜底默认值：

```java
import com.team4u.framework.config.core.annotation.ConfigKey;
import com.team4u.framework.config.core.annotation.ConfigPrefix;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigPrefix("server")
public class AppConfig {
    private String name;
    private int port = 8080; // 字段初始值即为兜底默认值
    
    @ConfigKey("connect-timeout") // 支持中划线松散绑定
    private long connectTimeout = 3000L;
    
    private DbConfig db; // 支持嵌套对象自动代理
}

@Getter
@Setter
public class DbConfig {
    private String url;
    private String username = "root";
}
```

### 创建代理并直接使用

```java
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.ConfigProxyContext;
import com.team4u.framework.config.core.ConfigProxyCreator;
import com.team4u.framework.config.core.proxy.ConfigProxyFactory;

public class ProxyQuickStart {
    public static void main(String[] args) {

        // 过渡期显式提供创建器；Task 9 后可直接引入 team4u-config-proxy
        ConfigProxyCreator creator = new ConfigProxyCreator() {
            @Override
            public <T> T create(ConfigProxyContext context, String prefix, Class<T> configType) {
                return new ConfigProxyFactory(context.converterRegistry())
                        .createLiveProxy(context.manager(), prefix, configType);
            }
        };
        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .proxyCreator(creator)
                .build();

        // 创建实时动态代理对象（Live Mode）
        AppConfig config = manager.createProxy(AppConfig.class);

        // 直接通过 Getter 访问，享受强类型提示与重构安全
        System.out.println("应用名称: " + config.getName());
        System.out.println("端口号: " + config.getPort());
        System.out.println("连接超时: " + config.getConnectTimeout() + "ms");
        System.out.println("数据库用户: " + config.getDb().getUsername());
    }
}
```

---

## 下一步

- 掌握 Live 模式与 Pinned 模式、注解与松散绑定：[类型安全代理与注解](config-proxy.md)
- 接入数据库与多源优先级覆盖：[多源配置与数据库扩展](config-source.md)
- 配置防抖重载与业务变更监听：[热重载与变更监听](config-reload.md)
- 配置驱动动态对象创建与优雅关闭：[配置驱动实例生命周期](config-instance.md)
- 查看微服务实战与单测支持：[实战案例与测试支持](config-sample.md)
