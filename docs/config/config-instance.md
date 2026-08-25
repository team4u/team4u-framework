# 配置驱动实例生命周期

在企业级基础架构中，经常面临“**配置变更 -> 运行时组件实例热重建与安全替换**”的诉求（例如：动态多租户数据源、动态 HTTP 客户端连接池、动态消息队列消费者、动态限流/路由规则）。

`team4u-config-core` 提供了 `ConfigDrivenRegistry<T>` 组件，统一治理对象的创建、热替换与资源优雅销毁。

---

## 核心设计理念

```mermaid
graph TD
    Change[配置中心变更信号 key: clients.sms-gateway] --> Listener[ConfigDrivenRegistry 监听回调]
    
    Listener --> CheckDel{newValue 是否为空或被删除}
    CheckDel -->|是 (删除/Tombstone)| Remove[从 instanceCache 移除<br/>调用 oldInstance.close 释放资源]
    CheckDel -->|否 (更新/新增)| Build[调用 instanceFactory.apply 构建新实例]
    
    Build --> TryBuild{构建新实例是否成功}
    TryBuild -->|失败抛出异常| KeepOld[打印错误日志<br/>保留旧实例继续对外服务 (业务不中断)]
    TryBuild -->|成功返回 newInstance| Swap[更新 instanceCache.put<br/>安全替换为新实例]
    Swap --> CloseOld[若旧实例实现了 AutoCloseable<br/>自动调用 oldInstance.close 优雅关闭]
```

1. **安全热替换 (Safe Swap)**：
   - 收到配置变更通知后，**先尝试使用新配置构建新实例**；
   - 只有在新实例构建成功后，才执行缓存引用的原子替换；
   - 若新配置存在格式错误、网络不可达等导致构建失败，系统会捕获异常并告警，**继续保留旧实例对外服务**，保证系统高可用与业务连续性。
2. **资源优雅关闭 (Graceful Shutdown)**：
   - 当旧实例被替换淘汰，或配置被物理删除/标记失效（Tombstone）时，框架自动检测其实例是否实现了 `java.lang.AutoCloseable` 接口；
   - 若实现，则自动调用 `close()` 方法释放底层网络连接、线程池或句柄，杜绝连接泄漏和内存溢出。
3. **延迟初始化与 O(1) 极速读取**：
   - 首次通过 `get(configKey)` 访问时，按需执行延迟构建（`computeIfAbsent`）；
   - 后续读取直接命中内部 `ConcurrentHashMap`，无反射与反序列化开销。
4. **监听器与实例全生命周期销毁 (`destroy()`)**：
   - 调用 `destroy()` 时，首先注销与 `ConfigManager` 的监听句柄，随后遍历所有已缓存的实例执行 `closeQuietly()` 彻底释放资源。

---

## 完整实战示例：动态 HTTP 客户端连接池

### 1. 定义受配置驱动的业务组件
实现 `AutoCloseable` 接口以支持优雅关闭：

```java
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DynamicHttpClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DynamicHttpClient.class);

    @Getter
    private final String clientName;
    private final String endpoint;
    private final int timeout;
    private final boolean isClosed;

    public DynamicHttpClient(String clientName, String endpoint, int timeout) {
        this.clientName = clientName;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.isClosed = false;
        log.info("初始化 HTTP 客户端连接池: name={}, endpoint={}, timeout={}ms", clientName, endpoint, timeout);
    }

    public String sendRequest(String path) {
        if (isClosed) {
            throw new IllegalStateException("Client is already closed: " + clientName);
        }
        return "Response from [" + endpoint + path + "] within " + timeout + "ms";
    }

    @Override
    public void close() {
        log.info("优雅关闭旧的 HTTP 客户端连接池: name={}, endpoint={}", clientName, endpoint);
        // 执行底层 Apache HttpClient / OkHttp / Netty 连接池销毁
    }
}
```

### 2. 初始化 `ConfigDrivenRegistry`

```java
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.serializer.json.JsonUtil;
import java.util.Map;

public class HttpClientRegistryManager {

    public static void main(String[] args) {
        ConfigManager configManager = ConfigManager.global();

        // 创建配置驱动的实例注册表
        ConfigDrivenRegistry<DynamicHttpClient> clientRegistry = new ConfigDrivenRegistry<>(
                configManager,
                "clients.", // 监听 "clients.*" 配置前缀
                rawJsonConfig -> {
                    // 工厂函数：将 JSON 字符串解析为配置并构造 DynamicHttpClient
                    Map<String, Object> conf = JsonUtil.toMap(rawJsonConfig);
                    String name = (String) conf.get("name");
                    String endpoint = (String) conf.get("endpoint");
                    int timeout = ((Number) conf.getOrDefault("timeout", 3000)).intValue();
                    return new DynamicHttpClient(name, endpoint, timeout);
                }
        );

        // 模拟配置中心配置:
        // clients.sms={"name":"sms-client","endpoint":"https://sms.aliyun.com","timeout":5000}
        
        // 1. 获取实例（首次调用延迟构建，后续 O(1) 缓存命中）
        DynamicHttpClient smsClient = clientRegistry.get("clients.sms");
        System.out.println(smsClient.sendRequest("/send"));

        // 2. 当配置中心推送新的 JSON 更新时：
        // ConfigDrivenRegistry 会自动构建新 DynamicHttpClient -> 替换缓存 -> 自动调用旧 DynamicHttpClient.close()

        // 3. 应用关闭时释放全部资源
        // clientRegistry.destroy();
    }
}
```

---

## 框架内部关键实现解析

`ConfigDrivenRegistry.java` 的核心热更新逻辑如下：

```java
private void onConfigChanged(String key, String oldValue, String newValue) {
    if (newValue == null || newValue.trim().isEmpty()) {
        // 配置移除/Tombstone 时清理缓存并释放旧资源
        removeAndClose(key);
        return;
    }

    // 安全替换：先构建新实例，成功后执行替换以保证高可用
    try {
        T newInstance = createInstance(key, newValue);
        if (newInstance != null) {
            T oldInstance = instanceCache.put(key, newInstance);
            if (oldInstance != null && oldInstance != newInstance) {
                closeQuietly(oldInstance);
            }
        }
    } catch (Exception e) {
        // 热更新构建失败时保留旧实例，确保服务连续性
        log.error("Failed to hot-reload instance for key [{}]. Keeping the old instance.", key, e);
    }
}
```
