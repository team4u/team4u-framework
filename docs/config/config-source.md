# 多源配置与数据库扩展

`team4u-config` 支持同时注册多个不同介质的配置源，并通过优先级策略链与聚合器将其无缝合并为一个全局不可变的配置快照。

---

## 内置配置源

框架内置了多种开箱即用的配置源，均实现了 `ConfigSource` 接口：

| 配置源类名 | 介质与功能说明 | 推荐优先级数值 |
| :--- | :--- | :--- |
| `SystemEnvConfigSource` | 聚合 JVM 系统属性（`-D` 启动参数）与操作系统环境变量（低优先级），自动将 `APP_PORT` 归一化为 `app.port` | 高优先级 (如 -100 或 0) |
| `DbConfigSource` | 从关系型数据库表动态拉取业务配置（来自 `team4u-config-db` 模块） | 业务优先 (如 100) |
| `PropertiesConfigSource` | 本地 `.properties` 文件或类路径资源 | 基础兜底 (如 500) |
| `InMemoryConfigSource` | 内存存储容器，同时兼具 `ConfigWatcher` 功能，支持即时修改与测试隔离 | 测试/动态注入 (如 0) |

> [!IMPORTANT]
> **优先级规则**：遵循 `OrderedPolicy` 规范，**数值越小优先级越高**（即 `-100` 优于 `0`，`0` 优于 `100`）。在多源覆盖时，数值小的配置源将优先保留。

---

## 多源优先级聚合与 Tombstone (墓碑) 机制

### 1. 优先级覆盖原理 (`SnapshotAggregator`)
在快照构建阶段，`SnapshotAggregator` 接收按优先级升序排序的配置源列表，按序对每个源的配置映射执行 `putIfAbsent`：

```mermaid
graph TD
    subgraph 优先级排序 (数值越小越优先)
        S1[源 1: SystemEnv, Priority = 0]
        S2[源 2: DbConfig, Priority = 100]
        S3[源 3: Properties, Priority = 500]
    end

    S1 -->|1. 写入 finalMap.putIfAbsent| FM[合并结果映射表]
    S2 -->|2. 写入已空缺的 key| FM
    S3 -->|3. 填充剩余默认值| FM
```

因此，高优先级源中的键会首先占据位置，低优先级源中的同名键将被自动忽略。

### 2. Tombstone (墓碑) 失效机制
在多源配置体系中，“删除配置”如果仅仅是物理移除该键，会导致低优先级源中的旧值“死灰复燃”。

为此，框架引入了 **Tombstone 机制**：
- 约定 `ConfigSource.TOMBSTONE_VALUE`（即 `null`）为墓碑哨兵值。
- 当高优先级数据源显式返回 `TOMBSTONE_VALUE` 时，聚合器会将其视为“已显式删除”，在最终快照中将此键标记为失效（`ConfigEntry.isEmptyOrDeleted()` 返回 `true`），**绝对不会回退到低优先级源的值**。

#### `delete` 与 `remove` 的区别（以 `InMemoryConfigSource` 为例）
- `delete(key)`：将该键的值设置为 `TOMBSTONE_VALUE`。在多源聚合时，显式屏蔽所有低优先级源中的该项配置。
- `remove(key)`：物理从当前内存映射中移除该键。低优先级源中的同名配置将重新暴露并生效。

---

## 内置源使用详解

### 1. `SystemEnvConfigSource`
自动结合 JVM 属性与环境变量，并在加载时自动为环境变量生成规范化的点分小写键副本：
```java
// APP_SERVER_PORT=9090 会自动生成副本 app.server.port=9090
SystemEnvConfigSource envSource = new SystemEnvConfigSource("SystemEnv", 0);
```

### 2. `PropertiesConfigSource`
支持通过 `Properties` 对象或从 ClassLoader 资源路径加载：
```java
// 方式 A：从 ClassPath 资源加载
PropertiesConfigSource fileSource = PropertiesConfigSource.fromResource(
        "LocalProperties", 
        500, 
        "config/app.properties"
);

// 方式 B：从已有的 Properties 对象构建
Properties props = new Properties();
props.setProperty("app.timeout", "5000");
PropertiesConfigSource directSource = new PropertiesConfigSource("CustomProps", 500, props);
```

### 3. `InMemoryConfigSource`
内存配置源同时实现了 `ConfigSource` 与 `ConfigWatcher`：
```java
InMemoryConfigSource memSource = new InMemoryConfigSource("MemoryMock", 10);

// 写入配置
memSource.put("feature.toggle", "true");

// 写入并立即发送变更信号触发全局重载
memSource.putAndRefresh("feature.toggle", "false");

// 标记失效 (Tombstone)
memSource.delete("feature.toggle");

// 物理移除
memSource.remove("feature.toggle");
```

---

## 数据库扩展模块 (team4u-config-db)

`team4u-config-db` 提供了从关系型数据库（MySQL、PostgreSQL、Oracle、H2 等）全量加载配置与基于时间戳轮询探测热更新的能力。

### 1. 数据库建表 DDL
默认表名为 `system_config`：

```sql
CREATE TABLE `system_config` (
    `id`           BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `config_type`  VARCHAR(64)  NOT NULL COMMENT '配置类型/前缀 (例如 app, datasource)',
    `config_key`   VARCHAR(128) NOT NULL COMMENT '配置键 (例如 port, max_active)',
    `config_value` TEXT         NULL     COMMENT '配置值',
    `enabled`      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '状态: 1-启用, 0-禁用/删除 (自动转为 Tombstone)',
    `update_time`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间 (用于变更探测)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_type_key` (`config_type`, `config_key`),
    KEY `idx_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统全局配置表';
```

### 2. 自定义表结构映射 (`DbConfigOptions`)
如果已有数据表的字段名不同，可通过 `DbConfigOptions` 自定义映射：

| 属性方法 | 默认常量 | 说明 |
| :--- | :--- | :--- |
| `setTableName(...)` | `DEFAULT_TABLE_NAME = "system_config"` | 配置表名称 |
| `setConfigTypeColumn(...)` | `DEFAULT_CONFIG_TYPE_COLUMN = "config_type"` | 配置类型列名（作为 Key 的前缀） |
| `setConfigKeyColumn(...)` | `DEFAULT_CONFIG_KEY_COLUMN = "config_key"` | 配置键列名 |
| `setConfigValueColumn(...)` | `DEFAULT_CONFIG_VALUE_COLUMN = "config_value"` | 配置值列名 |
| `setEnabledColumn(...)` | `DEFAULT_ENABLED_COLUMN = "enabled"` | 启用状态列名（值为 0 时映射为 Tombstone 失效标记） |
| `setUpdateTimeColumn(...)` | `DEFAULT_UPDATE_TIME_COLUMN = "update_time"` | 更新时间列名（用于 `DbConfigWatcher` 轮询探测变更） |

### 3. 配置源与监听器装配示例

```java
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.db.DbConfigOptions;
import com.team4u.framework.config.db.DbConfigSource;
import com.team4u.framework.config.db.DbConfigWatcher;
import javax.sql.DataSource;

// 1. 获取业务 DataSource
DataSource dataSource = getDataSource();

// 2. 定制表字段映射选项
DbConfigOptions dbOptions = new DbConfigOptions()
        .setTableName("sys_runtime_config")
        .setConfigTypeColumn("module_name")
        .setConfigKeyColumn("property_key")
        .setConfigValueColumn("property_val")
        .setEnabledColumn("is_active")
        .setUpdateTimeColumn("gmt_modified");

// 3. 创建数据库配置源 (优先级 100)
DbConfigSource dbSource = new DbConfigSource("MySQL-Main", 100, dataSource, dbOptions);

// 4. 创建数据库轮询监听器 (每 3 秒检测一次 MAX(update_time))
DbConfigWatcher dbWatcher = new DbConfigWatcher(dataSource, 3, dbOptions);

// 5. 组装至配置管理器
ConfigManager manager = ConfigManager.builder()
        .addSource(dbSource)
        .addWatcher(dbWatcher)
        .build();
```

### 4. `DbConfigWatcher` 变更探测机制
- 每次轮询仅执行一条轻量 SQL：`SELECT MAX(update_time) AS max_time FROM <table_name>`。
- 启动时自动记录基线时间戳 (`lastMaxTimestamp`)。
- 当探测到 `currentMax > lastMaxTimestamp` 时，触发 `changeSignal.run()` 发送重载信号。
- 内置错误计数器与异常隔离机制，数据库瞬时抖动不会导致监听线程退出。
