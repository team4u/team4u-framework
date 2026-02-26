# Team4u Config DB 扩展模块

[返回总目录](../README.md)

`team4u-config-db` 提供从关系型数据库（如 MySQL、H2 等）中动态加载配置项的能力。

## 核心特性

* **全量与增量加载**：支持系统启动时的全量加载，以及运行时基于时间戳的增量配置同步。
* **分级 Key 映射**：自动将数据库中的 `config_type` 和 `config_key` 拼接为 `type.key` 格式，实现配置项的逻辑隔离。
* **软删除支持**：通过 `enabled` 字段支持配置项的逻辑删除，自动转化为框架底层的 Tombstone（墓碑）语义。
* **高灵活性配置**：支持自定义表名和所有关键字段名。

## 快速入门

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-config-db</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 准备数据库表

默认情况下，框架期望存在名为 `system_config` 的表：

```sql
CREATE TABLE `system_config` (
    `id`           BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT,
    `enabled`      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0: 禁用/删除, 1: 启用',
    `config_type`  VARCHAR(32)  NOT NULL COMMENT '配置分类/前缀',
    `config_key`   VARCHAR(50)  NOT NULL COMMENT '配置键',
    `config_value` VARCHAR(500) NOT NULL COMMENT '配置值',
    `update_time`  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uniq_config` (`config_type`, `config_key`)
);
```

### 3. 使用示例

```java
// 1. 准备数据源
DataSource dataSource = ...;

// 2. 创建配置源（可选传入 DbConfigOptions 自定义表/字段名）
DbConfigOptions options = new DbConfigOptions().setTableName("my_config");
DbConfigSource source = new DbConfigSource("DB-Main", 100, dataSource, options);

// 3. 创建变更监听器
DbConfigWatcher watcher = new DbConfigWatcher(dataSource, 5, options);

// 4. 加载到管理器
ConfigManager manager = ConfigManager.builder()
    .addSource(source)
    .addWatcher(watcher)
    .build();
```

## 配置项说明 (DbConfigOptions)

| 属性                  | 默认值             | 说明                  |
|:--------------------|:----------------|:--------------------|
| `tableName`         | `system_config` | 配置表名                |
| `configTypeColumn`  | `config_type`   | 配置类型字段（映射为 Key 的前缀） |
| `configKeyColumn`   | `config_key`    | 配置键字段               |
| `configValueColumn` | `config_value`  | 配置值字段               |
| `enabledColumn`     | `enabled`       | 状态字段（0 表示禁用/删除）     |
| `updateTimeColumn`  | `update_time`   | 更新时间字段（用于增量拉取及探测变更） |
