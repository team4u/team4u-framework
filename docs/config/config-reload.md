# 热重载与变更监听

`team4u-config` 提供了完备的配置热重载与变更监听体系，保障配置变更秒级生效、防抖平滑过渡且绝不影响系统稳定性。

---

## 变更感知与防抖重载架构

```mermaid
graph TD
    W1[DbConfigWatcher 数据库轮询] -->|检测到变动| Signal[发送 changeSignal 回调]
    W2[Custom ConfigWatcher 文件/MQ] -->|检测到变动| Signal
    W3[InMemoryConfigSource.putAndRefresh] -->|手动触发| Signal
    
    Signal --> Debounce{HotReloadManager<br/>防抖窗口判断}
    Debounce -->|debounceWindowMs > 0| Delay[取消旧计时，重新延迟调度 500ms]
    Debounce -->|debounceWindowMs <= 0| Sync[直接在当前线程同步重载 (测试模式)]
    
    Delay --> Aggregate[SnapshotAggregator 重新聚合所有源]
    Sync --> Aggregate
    
    Aggregate -->|聚合成功| Swap[AtomicReference.getAndSet<br/>原子切换生效快照]
    Aggregate -->|异常失败| KeepOld[捕获异常并告警，保持旧快照稳定运行]
    
    Swap --> Diff[比对新旧快照差异 (New vs Old)]
    Diff --> Listeners[触发匹配的 ConfigChangeListener]
```

---

## 核心机制详解

### `ConfigWatcher` 探测与触发解耦
`ConfigSource` 专注于“读数据”，而 `ConfigWatcher` 专注于“感知变化”。两者清晰解耦：
- `ConfigWatcher.watch(Runnable changeSignal)`：在配置中心启动时被调用，当 watcher 探测到外部变化后，只需调用 `changeSignal.run()`。

### 防抖时间窗口 (Debounce Window) 与原子替换
当运维人员在配置中心批量提交修改（例如短时间内修改了数十个配置项）时，若每次变动都触发全量重载，将导致系统频繁抖动。

`HotReloadManager` 提供了高度优化的防抖调度机制：
- **默认防抖延迟**：`debounceWindowMs = 500ms`。在时间窗口内的后续变动信号会自动取消前一个未执行的任务并重新计时，将瞬时突发的多次变动合并为**单次原子重载**。
- **单元测试零延迟**：通过 `ConfigManager.builder().debounceWindow(0)` 或 `TestConfigContext` 将防抖窗口设为 `0` 或负数，变更信号将**直接在当前线程同步执行重载**，彻底消除单元测试中的 `Thread.sleep` 等待。
- **原子替换**：重载成功后，新生成的 `ConfigSnapshot` 通过 `AtomicReference.getAndSet()` 实现无锁原子替换。

---

## 注册配置变更监听器 (`registerChangeListener`)

业务层通常需要根据配置变动刷新本地连接池或内部缓存。`ConfigManager` 提供了细粒度的变更监听注册机制：

```java
import com.team4u.framework.config.core.ConfigManager;

ConfigManager manager = ConfigManager.global();

// 注册监听器并持有返回的可关闭句柄
AutoCloseable handle = manager.registerChangeListener("datasource.*", (key, oldValue, newValue) -> {
    if (newValue == null) {
        log.warn("配置项已被删除 (Tombstone): key={}", key);
    } else {
        log.info("配置项发生变更: key={}, oldValue={}, newValue={}", key, oldValue, newValue);
    }
});

// 当不需要继续监听时，显式注销该监听器
// handle.close();
```

### 模式匹配规则 (`isMatch`)
- **通配符模式（以 `*` 结尾）**：执行前缀匹配。例如 `"server.*"` 会匹配 `server.port`、`server.name`、`server.db.url`。
- **精确模式**：执行严格的字符串相等匹配。例如 `"app.max-connections"` 仅在该配置变动时触发。

### 异常隔离保障
若某个业务监听器的 `onChange` 回调抛出未捕获异常，`DefaultConfigManager` 会记录错误日志并继续通知后续的其他监听器，绝不会阻断整体事件分发流程。

---

## 占位符解析引擎 (`PlaceholderResolver`)

在快照构建阶段，`ConfigSnapshot` 自动调用 `PlaceholderResolver` 递归解析配置值中的动态占位符：

### 基础占位符与默认值
```properties
app.name=OrderService
# 基础引用
app.title=${app.name}
# 默认值回退：若 env 缺失，则默认取 "dev"
app.profile=${env:dev}
```

### 深度嵌套与动态键名
支持多级嵌套占位符解析：
```properties
env=prod
db.prod.host=10.0.0.1
db.dev.host=127.0.0.1
# 动态键名解析：先解析 ${env} 得到 prod，再解析 ${db.prod.host} 得到 10.0.0.1
current.db.host=${db.${env}.host}
```

### 循环依赖检测与递归保护
- **循环依赖防护**：在解析链条中维护 `visitedKeys` 栈。若出现循环引用（如 `a=${b}` 且 `b=${a}`），立即抛出 `IllegalArgumentException("Circular dependency detected for placeholder key: ...")`。
- **最大递归深度限制**：严格限制最大递归层级为 20 层（`MAX_DEPTH = 20`），防止异常配置导致 JVM 栈溢出。

---

## 可靠性设计与配置溯源

### 启动期快速失败 (Fail-Fast)
在 `ConfigManager` 初始化加载阶段，若底层关键配置源拉取失败抛出异常，系统将立即阻断应用启动，杜绝服务带着半残缺的配置上线。

### 运行期故障隔离与优雅回退
在运行期热重载阶段，如果某个外部配置源拉取超时或网络中断，`HotReloadManager` 会捕获异常并打印 Error 日志，同时**保留当前生效的旧快照继续提供服务**，确保核心业务不受网络抖动影响。

### 配置溯源能力 (`ConfigEntry`)
通过快照可随时获取配置的元数据与来源信息：

```java
manager.currentSnapshot().getEntry("server.name").ifPresent(entry -> {
    System.out.println("配置键名: " + entry.getKey());
    System.out.println("当前配置值: " + entry.getValue());
    System.out.println("数据来源: " + entry.getSourceName()); // 例如 "MySQL-Main" 或 "SystemEnv"
    System.out.println("更新时间戳: " + entry.getTimestamp());
    System.out.println("是否失效: " + entry.isEmptyOrDeleted());
});
```
