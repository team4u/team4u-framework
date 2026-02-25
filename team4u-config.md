这份文档是为大语言模型（LLM）编写代码而定制的**系统架构设计规范（SDD）**。它详细定义了 `FluxConfig` 框架的核心概念、接口契约、数据流转机制以及与旧框架的功能对齐策略。

---

# FluxConfig 现代配置中心框架设计文档 (v1.0)

## 1. 项目概述 (Project Overview)

**项目名称**: FluxConfig
**核心理念**: 不可变快照 (Immutable Snapshot)、反应式更新 (Reactive Update)、分层一致性 (Layered Consistency)。
**目标**: 替代遗留的 `team4u-config`，提供高性能、高并发安全、零依赖（Core）的现代配置管理方案。

## 2. 核心架构设计 (Architecture)

### 2.1 模块划分

* **flux-config-core**: 核心模块，定义 SPI、快照机制、绑定器、内存管理。**零依赖**。
* **flux-config-binder**: 高级特性，提供 Java Bean 绑定、注解支持 (`@ConfigValue`)。
* **flux-config-source-jdbc**: 数据库源实现（支持 MySQL/PostgreSQL）。
* **flux-config-source-file**: 文件系统源实现（支持 .properties, .yaml, .json）。
* **flux-config-spring-boot-starter**: Spring Boot 自动装配与 Environment 桥接。

### 2.2 核心数据流

1. **Sources** (Env, File, DB) -> **Aggregator** (优先级合并) -> **Snapshot V1** (不可变)。
2. **Watcher** (Timer/Event) -> **HotReloadManager** (防抖) -> **Aggregator** -> **Snapshot V2** -> **Atomic CAS**。
3. **Client** -> **Proxy** -> **Current Snapshot Reference** (Live View) OR **Pinned Snapshot** (Consistent View).

---

## 3. 领域模型 (Domain Models)

### 3.1 `ConfigEntry` (最小配置单元)

配置的原子载体，包含元数据。

```java
public record ConfigEntry(
    String key,         // 配置键 (e.g. "app.server.port")
    String value,       // 配置值
    String sourceName,  // 来源 (e.g. "JDBC-Primary", "File:/opt/conf/app.prop")
    long timestamp      // 更新时间戳
) {}

```

### 3.2 `ConfigSnapshot` (核心快照)

**特性**: 所有的写操作都在构造时完成，一旦构建，全量数据不可变。

```java
public class ConfigSnapshot {
    private final long version; // 版本号 (System.nanoTime() or Sequence)
    private final Map<String, ConfigEntry> entries;
    
    // O(1) 读取
    public Optional<String> get(String key);
    // 支持前缀搜索 (可选: 使用 Trie 树优化)
    public Map<String, String> getByPrefix(String prefix);
}

```

---

## 4. 核心接口契约 (Core SPI)

### 4.1 `ConfigSource` (数据源)

负责加载数据。不再强制全量加载，支持增量优化。

```java
public interface ConfigSource {
    String name();
    int order(); // 优先级，越小越高
    
    // 核心加载逻辑：返回当前源的所有配置
    Map<String, ConfigEntry> load();
    
    // 增量加载优化 (可选实现)，返回 null 表示不支持增量
    default Map<String, ConfigEntry> loadSince(long timestamp) { return null; }
}

```

### 4.2 `ConfigWatcher` (变更监听)

负责“发现”变化，而不是“加载”数据。

```java
public interface ConfigWatcher {
    // 注册监听回调
    void watch(Runnable changeSignal);
}

```

### 4.3 `ConfigBinder` (类型转换)

负责将 String 转换为强类型对象。

```java
public interface ConfigBinder {
    <T> T bind(ConfigSnapshot snapshot, String prefix, Class<T> type);
}

```

---

## 5. 关键机制实现 (Key Mechanisms)

### 5.1 热更新与防抖 (Hot Reload & Debounce)

**类名**: `HotReloadManager`

* **职责**:
1. 接收来自所有 `ConfigWatcher` 的信号。
2. **防抖**: 在 `windowTime` (e.g., 500ms) 内多次信号只触发一次刷新。
3. **刷新流程**:
* 遍历所有 `ConfigSource`。
* 调用 `loadSince` (如果支持) 或 `load`。
* 合并数据（高优先级覆盖低优先级）。
* 构建新的 `ConfigSnapshot`。
* `AtomicReference.compareAndSet` 替换全局引用。
* 触发 `ConfigChangeListener`。
* **异步持久化** L3 缓存到本地文件。





### 5.2 代理与一致性 (Snapshot-Aware Proxy)

**类名**: `ConfigProxyFactory` & `SnapshotAwareInvocationHandler`

* **双模式代理**:
1. **Live Mode (默认)**: 每次 `invoke` 时，从 `AtomicReference` 获取最新的 Snapshot 读取。
2. **Pinned Mode (快照模式)**: 通过 `getImmutableSnapshot()` 获取一个新的代理，该代理内部持有固定的 `ConfigSnapshot` 引用，不再随全局更新变化。


* **L2 缓存 (Versioned Memoization)**:
* Proxy 内部记录 `cachedValue` 和 `cachedVersion`。
* `invoke` 时检查 `currentSnapshot.version == cachedVersion`。
* 相等则直接返回 `cachedValue` (Zero Allocation)。
* 不等则重新绑定并更新缓存。



### 5.3 占位符解析 (Placeholder Resolution)

* **时机**: 在 `Aggregator` 构建 Snapshot 之前，或者在 `bind` 获取值时。建议在 `bind` 时解析以支持动态性。
* **逻辑**: 解析 `${app.name}`，从当前 Snapshot 中递归查找值。检测循环引用。

---

## 6. 旧框架功能对齐 (Feature Parity Mapping)

| 旧功能          | 旧类名                            | 新设计实现策略                                                              |
| --------------- | --------------------------------- | --------------------------------------------------------------------------- |
| **数据库源**    | `JdbcSimpleConfigRepository`      | 实现 `JdbcConfigSource`。支持 SQL 自定义，增加 `update_time` 增量查询逻辑。 |
| **MyBatis支持** | `MybatisSimpleConfigRepository`   | 属于 `flux-config-source-jdbc` 的扩展，提供 Mapper 适配器。                 |
| **文件源**      | `FileConfigRepository`            | 实现 `FileConfigSource`。使用 Java NIO `WatchService` 实现文件变更监听。    |
| **Classpath源** | `ResourceSimpleConfigRepository`  | 实现 `ClasspathConfigSource` (通常由 ClassLoader 加载，默认只读)。          |
| **组合/层级**   | `CompositeSimpleConfigRepository` | 内置于 `ConfigManager` 的聚合逻辑中，通过 `order()` 排序。                  |
| **动态刷新**    | `CacheableSimpleConfigRepository` | `HotReloadManager` (核心组件)。                                             |
| **Bean映射**    | `ConfigUtil.ofType`               | `ConfigBinder.bind` (支持 Java Bean, Record, List, Map)。                   |
| **监听器**      | `ConfigSubscriber`                | `ConfigManager.addListener(key, consumer)`。                                |
| **工具类**      | `ConfigUtil`                      | 提供静态门面 `FluxConfig`，但建议优先使用依赖注入。                         |
| **Spring集成**  | `SpringPropertySource...`         | `FluxConfigPropertySource` 注入到 Spring Environment。                      |

---

## 7. 详细设计规范 (Implementation Specifications)

### 7.1 核心接口定义 (Core Interfaces)

```java
// 核心管理器
public interface ConfigManager {
    // 获取当前最新快照
    ConfigSnapshot currentSnapshot();
    
    // 获取代理对象 (Live Mode)
    <T> T createProxy(String prefix, Class<T> interfaceType);
    
    // 注册变更监听
    void addChangeListener(String keyPattern, BiConsumer<String, String> listener);
}

// 快照感知接口 (用于 Proxy)
public interface SnapshotAware<T> {
    // 返回被“钉住”的配置快照对象
    T pin(); 
}

```

### 7.2 数据库表设计建议 (Schema)

推荐使用扁平化设计，兼容旧表需要适配器。

**标准新表**:

```sql
CREATE TABLE flux_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name VARCHAR(64),
    profile VARCHAR(32) DEFAULT 'default',
    config_key VARCHAR(128) NOT NULL,
    config_value LONGTEXT,
    version BIGINT DEFAULT 1,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT DEFAULT 0,
    UNIQUE INDEX idx_key (app_name, profile, config_key)
);

```

### 7.3 异常处理规范

* **加载失败**: 如果是非核心 Source (如 DB 挂了)，记录 Error 日志，降级使用 L3 本地缓存文件。如果是核心 Source 且无缓存，抛出 `ConfigInitializationException` 阻止启动。
* **解析失败**: Bean 绑定失败（类型不匹配）时，应返回默认值（如果提供）或抛出运行时异常，不应中断热更新线程。

---

## 8. 代码实现步骤 (Step-by-Step Implementation Plan)

请按照以下顺序生成代码：

1. **Phase 1: Core Skeleton**
* 定义 `ConfigEntry`, `ConfigSnapshot` (使用 `Map.copyOf` 保证不可变)。
* 定义 `ConfigSource` 和 `ConfigWatcher` 接口。
* 实现 `ConfigManager` 和 `HotReloadManager` (含 CAS 逻辑)。


2. **Phase 2: Sources Implementation**
* 实现 `EnvConfigSource` (读取 System.env 和 System.properties)。
* 实现 `FileConfigSource` (读取 .properties/.yaml)。
* 实现 `JdbcConfigSource` (基于 JDBC Template，无 ORM 依赖)。


3. **Phase 3: Binding & Proxy**
* 实现 `DefaultConfigBinder` (基于反射)。
* 实现 `SnapshotAwareInvocationHandler` (实现 L2 缓存、Live/Pinned 切换逻辑)。


4. **Phase 4: Compatibility & Spring**
* 实现 `LegacyDbAdapter` (适配旧的 `system_config` 表结构)。
* 编写 Spring Boot Starter 的 `AutoConfiguration`。


5. **Phase 5: L3 Caching (Failover)**
* 在 `HotReloadManager` 中集成 `LocalFileSnapshotPersister`。



---

此文档提供了编写 `FluxConfig` 所需的所有架构细节。请根据此文档生成具体的 Java 代码。

这是一个基于 **FluxConfig** 现代架构的使用示例。

为了让你更直观地理解，我将示例分为三个层次：

1. **基础用法**：类似旧 `ConfigUtil` 的直接获取。
2. **高级用法**：基于接口的**动态代理（Live Proxy）**。
3. **一致性用法**：解决“撕裂读取”问题的**快照锚定（Pinning）**。

---

### 1. 初始化 (Bootstrap)

首先，我们需要构建核心管理器。在 Spring 环境下这会自动完成，但在单元测试或非 Spring 环境中，你可以手动构建。

```java
// 手动构建 FluxConfig 实例
ConfigManager configManager = FluxConfigBuilder.create()
    // 添加环境变量源 (优先级 100)
    .addSource(new EnvConfigSource())
    // 添加数据库源 (优先级 10, 支持热更新)
    .addSource(new JdbcConfigSource("jdbc:mysql://...", "root", "password"))
    // 添加本地文件兜底 (优先级 999)
    .addSource(new FileConfigSource("/opt/app/config.properties"))
    .build();

// (可选) 初始化静态门面，兼容旧代码习惯
FluxConfig.init(configManager);

```

---

### 2. 场景一：直接获取单个值 (Direct Access)

这是最基础的用法，适用于偶尔需要获取某个配置，或者在脚本中使用。

**特点**：永远获取**最新**的值（Live Value）。

```java
public void simpleUsage() {
    // 1. 获取字符串 (使用 Optional 防止空指针)
    String appName = configManager.getString("app.name").orElse("MyApp");

    // 2. 获取整数 (自动类型转换)
    int timeout = configManager.getInt("server.timeout", 5000);

    // 3. 获取复杂类型 (List/Map)
    // 假设配置为: tags=a,b,c
    List<String> tags = configManager.bind("tags", new TypeReference<List<String>>(){});
    
    System.out.println("Current App: " + appName);
}

```

---

### 3. 场景二：获取配置对象 (Live Proxy) —— **推荐用法**

定义一个接口，FluxConfig 会自动生成代理实现。

**特点**：

* **动态性**：每次调用 getter 方法，都会读取内存中最新的配置。
* **零内存分配**：只要配置没变，代理内部会复用缓存的计算结果（L2 Cache）。

#### 第一步：定义配置接口

```java
// 只需要定义接口，不需要实现类
public interface ServerConfig {
    // 默认映射 key 为 prefix + method name (e.g., "server.host")
    String host();

    // 支持自定义注解映射
    @ConfigKey("port") 
    int port();

    // 支持默认值
    @DefaultValue("60")
    int maxConnections();
    
    // 支持嵌套对象
    MetricsConfig metrics();
}

```

#### 第二步：获取并使用代理

```java
public void proxyUsage() {
    // 创建代理对象，绑定 "server" 前缀
    ServerConfig config = configManager.createProxy("server", ServerConfig.class);

    // --- 模拟时间轴 ---
    
    // T1时刻: server.host=192.168.1.1
    System.out.println(config.host()); // 输出: 192.168.1.1

    // ... 后台发生了热更新，DB中 host 变为 192.168.1.2 ...

    // T2时刻: 直接调用同一个对象的同一个方法
    System.out.println(config.host()); // 输出: 192.168.1.2 (自动感知变化)
}

```

---

### 4. 场景三：解决“撕裂读取” (Transactional Consistency)

这是新框架的核心亮点。当你需要确保多个配置项在同一时刻的一致性时（例如 URL 和 Port 必须匹配），使用 **快照锚定 (Pinning)**。

```java
public void consistentUsage() {
    // 1. 获取代理对象 (通常由 Spring 注入)
    ServerConfig liveConfig = configManager.createProxy("server", ServerConfig.class);

    // ==========================================
    // 错误示范 (可能发生撕裂)
    // ==========================================
    // 假设中间发生了热更新，host 变了但 port 还没读到
    // connect(liveConfig.host(), liveConfig.port()); 

    // ==========================================
    // 正确示范 (快照锚定)
    // ==========================================
    
    // 1. 将代理强转为 SnapshotAware 接口 (所有 FluxConfig 代理都实现了此接口)
    SnapshotAware<ServerConfig> aware = (SnapshotAware<ServerConfig>) liveConfig;

    // 2. "钉住" 当前版本，获取一个不可变的快照副本
    // 这一步是原子的，获取到的 pinnedConfig 永远不会变
    ServerConfig pinnedConfig = aware.pin();

    // 3. 安全读取
    String host = pinnedConfig.host(); 
    int port = pinnedConfig.port();
    
    // 即使现在后台更新了 100 次，host 和 port 依然是匹配的旧值
    System.out.println("Using version: " + ((ConfigSnapshot)pinnedConfig).getVersion());
    
    database.connect(host, port);
}

```

---

### 5. Spring Boot 集成示例

在 Spring Boot 中，你通常不需要手动创建代理，直接注入接口即可。

```java
@Configuration
public class AppConfiguration {
    // 注册配置工厂
    @Bean
    public FluxConfigPropertySource fluxConfigPropertySource(ConfigManager manager) {
        return new FluxConfigPropertySource(manager);
    }
}

@Service
public class PaymentService {
    
    // 直接注入接口！
    // FluxConfig 的 Starter 会自动扫描并生成代理 Bean
    @Autowired
    private ServerConfig serverConfig;

    public void pay() {
        // 大多数时候直接用，获取最新值
        String host = serverConfig.host();
        
        // 如果需要一致性，在业务逻辑里手动 pin 一下
        if (requiresConsistency) {
             var safeConfig = ((SnapshotAware<ServerConfig>) serverConfig).pin();
             // ...
        }
    }
}

```

### 总结

* **简单场景** -> `FluxConfig.getString("key")`
* **常规业务** -> `interface` + `createProxy` (Live Mode)
* **关键事务** -> `((SnapshotAware)proxy).pin()` (Snapshot Mode)

这是一个基于 **FluxConfig** 现代架构的使用示例。

为了让你更直观地理解，我将示例分为三个层次：

1. **基础用法**：类似旧 `ConfigUtil` 的直接获取。
2. **高级用法**：基于接口的**动态代理（Live Proxy）**。
3. **一致性用法**：解决“撕裂读取”问题的**快照锚定（Pinning）**。

---

### 1. 初始化 (Bootstrap)

首先，我们需要构建核心管理器。在 Spring 环境下这会自动完成，但在单元测试或非 Spring 环境中，你可以手动构建。

```java
// 手动构建 FluxConfig 实例
ConfigManager configManager = FluxConfigBuilder.create()
    // 添加环境变量源 (优先级 100)
    .addSource(new EnvConfigSource())
    // 添加数据库源 (优先级 10, 支持热更新)
    .addSource(new JdbcConfigSource("jdbc:mysql://...", "root", "password"))
    // 添加本地文件兜底 (优先级 999)
    .addSource(new FileConfigSource("/opt/app/config.properties"))
    .build();

// (可选) 初始化静态门面，兼容旧代码习惯
FluxConfig.init(configManager);

```

---

### 2. 场景一：直接获取单个值 (Direct Access)

这是最基础的用法，适用于偶尔需要获取某个配置，或者在脚本中使用。

**特点**：永远获取**最新**的值（Live Value）。

```java
public void simpleUsage() {
    // 1. 获取字符串 (使用 Optional 防止空指针)
    String appName = configManager.getString("app.name").orElse("MyApp");

    // 2. 获取整数 (自动类型转换)
    int timeout = configManager.getInt("server.timeout", 5000);

    // 3. 获取复杂类型 (List/Map)
    // 假设配置为: tags=a,b,c
    List<String> tags = configManager.bind("tags", new TypeReference<List<String>>(){});
    
    System.out.println("Current App: " + appName);
}

```

---

### 3. 场景二：获取配置对象 (Live Proxy) —— **推荐用法**

定义一个接口，FluxConfig 会自动生成代理实现。

**特点**：

* **动态性**：每次调用 getter 方法，都会读取内存中最新的配置。
* **零内存分配**：只要配置没变，代理内部会复用缓存的计算结果（L2 Cache）。

#### 第一步：定义配置接口

```java
// 只需要定义接口，不需要实现类
public interface ServerConfig {
    // 默认映射 key 为 prefix + method name (e.g., "server.host")
    String host();

    // 支持自定义注解映射
    @ConfigKey("port") 
    int port();

    // 支持默认值
    @DefaultValue("60")
    int maxConnections();
    
    // 支持嵌套对象
    MetricsConfig metrics();
}

```

#### 第二步：获取并使用代理

```java
public void proxyUsage() {
    // 创建代理对象，绑定 "server" 前缀
    ServerConfig config = configManager.createProxy("server", ServerConfig.class);

    // --- 模拟时间轴 ---
    
    // T1时刻: server.host=192.168.1.1
    System.out.println(config.host()); // 输出: 192.168.1.1

    // ... 后台发生了热更新，DB中 host 变为 192.168.1.2 ...

    // T2时刻: 直接调用同一个对象的同一个方法
    System.out.println(config.host()); // 输出: 192.168.1.2 (自动感知变化)
}

```

---

### 4. 场景三：解决“撕裂读取” (Transactional Consistency)

这是新框架的核心亮点。当你需要确保多个配置项在同一时刻的一致性时（例如 URL 和 Port 必须匹配），使用 **快照锚定 (Pinning)**。

```java
public void consistentUsage() {
    // 1. 获取代理对象 (通常由 Spring 注入)
    ServerConfig liveConfig = configManager.createProxy("server", ServerConfig.class);

    // ==========================================
    // 错误示范 (可能发生撕裂)
    // ==========================================
    // 假设中间发生了热更新，host 变了但 port 还没读到
    // connect(liveConfig.host(), liveConfig.port()); 

    // ==========================================
    // 正确示范 (快照锚定)
    // ==========================================
    
    // 1. 将代理强转为 SnapshotAware 接口 (所有 FluxConfig 代理都实现了此接口)
    SnapshotAware<ServerConfig> aware = (SnapshotAware<ServerConfig>) liveConfig;

    // 2. "钉住" 当前版本，获取一个不可变的快照副本
    // 这一步是原子的，获取到的 pinnedConfig 永远不会变
    ServerConfig pinnedConfig = aware.pin();

    // 3. 安全读取
    String host = pinnedConfig.host(); 
    int port = pinnedConfig.port();
    
    // 即使现在后台更新了 100 次，host 和 port 依然是匹配的旧值
    System.out.println("Using version: " + ((ConfigSnapshot)pinnedConfig).getVersion());
    
    database.connect(host, port);
}

```

---

### 5. Spring Boot 集成示例

在 Spring Boot 中，你通常不需要手动创建代理，直接注入接口即可。

```java
@Configuration
public class AppConfiguration {
    // 注册配置工厂
    @Bean
    public FluxConfigPropertySource fluxConfigPropertySource(ConfigManager manager) {
        return new FluxConfigPropertySource(manager);
    }
}

@Service
public class PaymentService {
    
    // 直接注入接口！
    // FluxConfig 的 Starter 会自动扫描并生成代理 Bean
    @Autowired
    private ServerConfig serverConfig;

    public void pay() {
        // 大多数时候直接用，获取最新值
        String host = serverConfig.host();
        
        // 如果需要一致性，在业务逻辑里手动 pin 一下
        if (requiresConsistency) {
             var safeConfig = ((SnapshotAware<ServerConfig>) serverConfig).pin();
             // ...
        }
    }
}

```

### 总结

* **简单场景** -> `FluxConfig.getString("key")`
* **常规业务** -> `interface` + `createProxy` (Live Mode)
* **关键事务** -> `((SnapshotAware)proxy).pin()` (Snapshot Mode)