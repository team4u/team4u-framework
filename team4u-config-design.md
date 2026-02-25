# team4u-config 现代配置中心框架设计文档 (v1.0)

## 1. 项目概述 (Project Overview)

项目名称: team4u-config
核心理念: 不可变快照 (Immutable Snapshot)、反应式更新 (Reactive Update)、分层一致性 (Layered Consistency)。
目标: 采用纯粹的新架构提供高性能、高并发安全、基于 `team4u-framework` 核心特性的现代配置管理方案，替换并对齐遗留系统的功能诉求。

## 2. 核心架构设计 (Architecture)

### 2.1 Maven 工程模块划分 (Module Hierarchy)

为了保持 `team4u-framework` 根项目的整洁，避免随着配置源种类增加导致顶级模块泛滥，`team4u-config` 相关的模块将采用嵌套子项目 (Nested Sub-modules) 的结构进行组织：

* team4u-config (核心/父工程)
  * `team4u-config-core`: 核心模块，定义 SPI、快照机制、绑定器、内存管理。深度复用 `team4u-policy` 等核心组件，零额外依赖。
  * `team4u-config-extensions`: (聚合工程，存放各类非核心扩展的外部实现)
    * `team4u-config-source-jdbc`: 数据库源实现（支持复用现有的 JDBC 相关能力，适配旧的 `system_config` 表）。
    * `team4u-config-source-file`: 文件系统源实现（支持 .properties, .yaml 的加载与监听）。
    * *(未来可扩展的其他源如 redis, zookeeper 等...)*
  * `team4u-config-spring-boot-starter`: Spring Boot 自动装配工程，对接 Spring `Environment` 并支持代理 Bean 的注入。

### 2.2 核心数据流转

1. 配置加载: Sources (Env, File, DB 等) -> Aggregator (优先级合并) -> Snapshot V1 (不可变)。
2. 热更新: Watcher (Timer/Event) -> HotReloadManager (防抖机制) -> Aggregator -> Snapshot V2 -> Atomic CAS 替换。
3. 前端消费: Client -> Proxy -> Current Snapshot Reference (Live View 实时试图) 或 Pinned Snapshot (Consistent View 一致性视图)。

---

## 3. 领域模型 (Domain Models)

### 3.1 `ConfigEntry` (最小配置单元)

配置的原子载体，包含配置的键值和元数据。

```java
import lombok.Value;

@Value
public class ConfigEntry {
    String key;         // 配置键 (例如: "app.server.port")
    String value;       // 配置值 (若为 null，则代表该配置在此源中被删除或未定义)
    String sourceName;  // 来源 (例如: "JDBC-Primary", "File:/opt/conf/app.prop")
    long timestamp;     // 更新时间戳

    /
     * 判断当前配置项是否为空或已删除
     */
    public boolean isEmptyOrDeleted() {
        return value == null;
    }
}
```

### 3.2 `ConfigSnapshot` (核心快照)

特性: 所有的写操作都在构造时完成，一旦构建，全量数据不可变。基于 `Map.copyOf` 等机制保证并发安全。

```java
import lombok.Getter;

public class ConfigSnapshot {
    @Getter
    private final long version; // 版本号 (System.nanoTime() 或自增 Sequence)
    private final Map<String, ConfigEntry> entries;
    
    // 省略构造器...

    // O(1) 读取
    public Optional<String> get(String key);
    
    // 支持按前缀搜索检索嵌套配置
    public Map<String, String> getByPrefix(String prefix);
}
```

---

## 4. 核心接口契约 (Core SPI)

### 4.1 `ConfigSource` (数据源)

负责加载数据。复用 `team4u-framework` 的 `OrderedPolicy` 接口，从而天然具备基于 `priority()` 的排序和策略加载能力。

```java
import com.team4u.framework.policy.OrderedPolicy;

public interface ConfigSource extends OrderedPolicy {
    
    /
     * 数据源名称
     */
    String name();
    
    /
     * 核心加载逻辑：返回当前源的所有配置
     */
    Map<String, ConfigEntry> load();
    
    /
     * 增量加载优化 (可选实现)，返回 null 表示不支持增量
     *
     * @param timestamp 上次访问时间戳
     * @return 变更的配置项
     */
    default Map<String, ConfigEntry> loadSince(long timestamp) { 
        return null; 
    }
}
```

*说明：通过继承 `OrderedPolicy`，可以直接利用 `ContextPolicy.HIGHEST`, `ContextPolicy.LOW` 等常数来控制不同环境配置的覆盖优先级。越小优先级越高。此外，对于 `loadSince` 的实现（如 JDBC 数据源），不要依赖绝对的严格时间戳匹配以防止时钟漂移 (Clock Drift) 和漏单，应使得传入的时间戳向后拨动一个 安全时间窗口 (Safe Window, 例如 1 分钟) 进行重叠读取，然后在内存中进行最终合并。*

### 4.2 `ConfigWatcher` (变更监听)

负责“发现”变化（如文件变更、数据库定时轮询），而不是直接处理数据加载。

```java
public interface ConfigWatcher {
    
    /
     * 初始化 Watcher 资源 (如建立数据库长连接、启动定时线程等)
     * 在 ConfigManager 启动或注册此 Watcher 时被调用
     */
    default void init() {}
    
    /
     * 注册监听回调，当源数据有变更嫌疑时调用 changeSignal
     * 
     * @param changeSignal 变更信号触发器
     */
    void watch(Runnable changeSignal);
    
    /
     * 销毁并释放资源 (如关闭文件句柄、停止线程)
     * 在框架生命周期结束或移除该 Watcher 时调用
     */
    default void destroy() {}
}
```

### 4.3 `ConfigBinder` (类型转换与绑定)

负责将 `String` 类型的配置映射并转换为强类型对象或 Java Bean。

```java
public interface ConfigBinder {
    <T> T bind(ConfigSnapshot snapshot, String prefix, Class<T> type);
}
```

* 映射约定 (Naming Convention)：默认支持智能松散绑定 (Smart Relaxed Binding)。例如 Java 接口中的方法名 `maxDbConnections()` 可以自动匹配配置文件中以下常见格式：
   - kebab-case (中划线): `max-db-connections` (最推荐的外部配置风格)
   - snake_case (下划线): `max_db_connections` (常用于环境变量)
   - camelCase (驼峰式): `maxDbConnections` (代码原样)
   - dot.case (点分隔): `max.db.connections`
   若需精准匹配打破规则，可通过 `@ConfigKey` 此类注解指定确切配置键。

---

## 5. 关键机制实现 (Key Mechanisms)

### 5.1 热更新与聚合防抖 (Hot Reload & Debounce)

核心组件: `HotReloadManager`

* 职责:
    1. 接收来自所有 `ConfigWatcher` 的更新信号。
    2. 防抖 (Debounce): 在 `windowTime` (如 500ms) 内多次密集信号只触发一次配置全环节刷新。
    3. 刷新流程:
       * 通过聚合器遍历所有 `ConfigSource`（已按照 `priority()` 排好序）。
       * 调用 `loadSince` 或全量 `load` 加载最新配置。
       * 合并规则 (聚合机制)：
         * 按照优先级合并数据（高优先级覆盖低优先级配置）。
         * 删除语义与穿透 (Tombstone)：如果高优先级的源（如数据库）中删除了某个配置，该源返回的 `ConfigEntry` 的 `value` 应为 `null` (或在最新 `load` 结果中不再包含该 Key)。聚合器若发现当前最高优先级的源未提供有效值（不存在或值为 `null`），则简单回退，继续寻找下一个较低优先级数据源中的同名配置值。
       * 启动与热更新的容错策略 (Fail-Fast vs Fail-Safe)：
         * 系统首次启动 (Initial Load)：如果任何核心数据源加载失败（如 JDBC 连接超时），框架将快速失败 (Fail-Fast) 并抛出异常，阻断应用启动，拒绝在配置不全的“半残”状态下运行。
         * 热更新过程 (Hot Reload)：如果某个数据源加载失败，本次热更新整体中止（放弃产生新版本快照），并记录 Error 级别日志。系统将保留使用当前有效的旧快照，以此避免因配置加载失败导致状态不一致。一旦源恢复正常，下次重试即可拉取到最新变更。
       * 构建全新的 `ConfigSnapshot` 实例。
       * 使用 `AtomicReference.compareAndSet` 原子的方式替换全局引用的 Snapshot。
       * 触发针对特定 Key 的 `ConfigChangeListener`。

### 5.2 代理与一致性 (Snapshot-Aware Proxy)

核心组件: `ConfigProxyFactory` & `SnapshotAwareInvocationHandler`

* 双模式动态代理:
    1. Live Mode (默认): 每次调用接口方法 (如 `config.port()`) 时，动态从系统 `AtomicReference` 指向的最新的 `Snapshot` 读取数据。
    2. Pinned Mode (快照锚定模式): 通过代理对象的强转调用 `pin()` 获取一个新的固定快照代理。该代理内含绑定的老版本 `ConfigSnapshot`，不会随全局更新而变，以防破坏一致性或产生“撕裂读取”。

* L2 缓存优化 (Zero Allocation):
    * 代理内部记录上一次求值的 `cachedValue` 和 `cachedVersion`。
    * 下次调用时，如果全局 `currentSnapshot.version == cachedVersion`，直接返回 `cachedValue`。避免频繁的类型转化与反射耗时。

### 5.3 占位符解析 (Placeholder Resolution)

* 在 `ConfigBinder` 获取特定值时进行占位符解析（支持嵌套求值，例如：`${app.port}` 引用快照中的其他 Key）。
* 循环依赖熔断机制：必须包含循环引用检测。实现解析器时，需维护一个当前解析链路的集合（如 `Set<String> visitedKeys`）。一旦发现当前要解析的 Key 已经存在于 `visitedKeys` 中，或达到设定的最大递归深度（如嵌套 10 层），应立即抛出 `IllegalArgumentException` (或自定义 `CircularDependencyException`)，防止产生 StackOverflow 异常或死循环。

---

## 6. 旧框架功能对齐 (Feature Parity Mapping)

| 旧功能诉求        | 遗留相关类参考                    | `team4u-config` 新设计对齐机制                                               |
| ----------------- | --------------------------------- | ---------------------------------------------------------------------------- |
| 数据库源          | `JdbcSimpleConfigRepository`      | `JdbcConfigSource`。复用系统已有 JdbcTemplate，增加 `update_time` 增量查询。 |
| 文件源            | `FileConfigRepository`            | `FileConfigSource`。支持 `WatchService` 以实现 File Watcher。                |
| 配置组合/分层覆盖 | `CompositeSimpleConfigRepository` | 直接由 `OrderedPolicy#priority()` 和核心 `Aggregator` 进行合并覆盖。         |
| 动态刷新缓存      | `CacheableSimpleConfigRepository` | `HotReloadManager`CAS 原子快照无锁读写机制。                                 |
| 类型转化与映射    | `ConfigUtil.ofType`               | `ConfigBinder.bind`（对接 Jackson / Java Reflection 等）。                   |
| 监听器支持        | `ConfigSubscriber`                | `ConfigManager.addListener(key, callback)` 触发增量。                        |
| Spring 集成       | `SpringPropertySource...`         | 构建 `Team4uConfigPropertySource` 适配 Spring `Environment` 并支持代理注入。 |

---

## 7. 详细设计规范 (Implementation Specifications)

### 7.1 核心对外 API (Core Services)

```java
// 系统级总控接口
public interface ConfigManager {
    // 获取当前最高版本快照
    ConfigSnapshot currentSnapshot();
    
    // 生成配置接口类型的动态代理实例 (默认 Live Mode)
    <T> T createProxy(String prefix, Class<T> interfaceType);
    
    // 基础键值获取 (快捷方式)
    Optional<String> getString(String key);
    
    // 监听配置点变更 (支持精准匹配或 startWith 前缀匹配)
    void addChangeListener(String keyPattern, ConfigChangeListener listener);
}

// 明确的配置变更回调接口
public interface ConfigChangeListener {
    /
     * 配置变更回调
     *
     * @param key      发生变更的精确配置键
     * @param oldValue 旧值 (如果之前不存在或已删除，则可能为 null)
     * @param newValue 新值 (如果被删除，则为 null)
     */
    void onChange(String key, String oldValue, String newValue);
}

// 动态代理感知接口，用于快照切换
public interface SnapshotAware<T> {
    // “钉住” 当前配置状态，返回一致的固定配置快照
    T pin(); 
}
```

### 7.2 数据库表设计建议 (Schema Recommendation)

```sql
CREATE TABLE sys_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name VARCHAR(64) DEFAULT 'default',
    profile VARCHAR(32) DEFAULT 'default',
    config_key VARCHAR(128) NOT NULL,
    config_value LONGTEXT,
    version BIGINT DEFAULT 1,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT DEFAULT 0,
    UNIQUE INDEX idx_key (app_name, profile, config_key)
) COMMENT '现代化配置中心表';
```

### 7.3 容错与异常处理 (Fault Tolerance)

* 隔离性设计：如果某些非主力的来源加载失败（例如外部 HTTP 配置中心宕机），应只打出错误日志，利用 L3 层本地兜底快照 (`LocalFileSnapshotPersister`)。
* 类型安全：当业务请求转为 `Integer` 而配置是不合规的字符时，返回接口预设的 `@DefaultValue` 或者原路返回旧值，杜绝核心业务异常停止。

---

## 8. 代码实现指导与实施序列 (Execution Plan)

后续开发请严格遵循下述迭代路径进行：

1. 第一阶段: 通用核心 (Core Kernel) 
   * 依赖：JDK 8、`team4u-policy` (OrderedPolicy 等)、`hutool-all` (用于 JSON / Bean 等类型转换的补充工具)。
   * 制品：`ConfigEntry`, `ConfigSnapshot`, `ConfigSource` (复用OrderedPolicy), `ConfigManager`, `HotReloadManager`。
2. 第二阶段: 数据源与转化 (Sources & Binding)
   * 实现：`EnvConfigSource`, `FileConfigSource`, `JdbcConfigSource`。
   * 开发基于反射和类型推断的 `ConfigBinder`，处理嵌套和泛型。
3. 第三阶段: 动态代理层 (Dynamic Proxy)
   * 实现：`ConfigProxyFactory`, `SnapshotAwareInvocationHandler` (负责 LRU / L2 Cache 判断)。
4. 第四阶段: Spring Boot 与框架对齐 (Integration)
   * 构建 Spring Boot AutoConfiguration。
   * 实现 `@EnableTeam4uConfig` 注解和 `Environment` 桥接，支持 Spring `@Value` 以及定制化 `@ConfigValue`（待定）。

此规范为 `team4u-config` 新架构完整执行指南，后续编码过程均应恪守此结构以及“不可变快照”的核心信仰进行设计。
