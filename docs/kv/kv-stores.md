# 存储后端

除内存实现外，组件提供 JDBC 与 Redis 两个共享存储后端。内存与 JDBC 后端已在 CI 跑 [契约测试](kv-test.md)（行为一致）；Redis 后端目前由单元测试验证命令映射，契约测试待接入真实环境。

## 后端对比

| 后端 | 模块 | `IF_ABSENT` | CAS | 扫描 | 原生TTL | 互斥范围 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `InMemoryKvStore` | kv-core | `compute` | `compute` | 遍历 | 惰性判定 | 当前进程 |
| `JdbcKvStore` | kv-store-jdbc | 唯一索引 | 条件 UPDATE | SQL | 惰性判定 | 连接该库的实例 |
| `RedisKvStore` | kv-store-redis | SETNX | Lua 脚本 | SCAN | ✅ | 连接该 Redis 的实例 |

## JDBC：JdbcKvStore

基于原生 JDBC，仅依赖一个 `DataSource`：

```java
KvStore kv = new JdbcKvStore(dataSource);

// 可选配置 + 测试时钟
KvStore kv = new JdbcKvStore(dataSource,
        new JdbcKvStore.Config().setTableName("kv_store").setAutoCreateTable(true),
        clock);
```

默认建表 DDL（H2 与 MySQL 语法兼容，`autoCreateTable=true` 时启动自动执行）：

```sql
CREATE TABLE IF NOT EXISTS kv_store (
    space     VARCHAR(100)  NOT NULL,
    name      VARCHAR(255)  NOT NULL,
    kv_value  VARCHAR(4000) NOT NULL,
    expire_at BIGINT        NOT NULL DEFAULT 0,   -- epoch 毫秒，0 为永不过期
    PRIMARY KEY (space, name)
);
```

> 列名 `kv_value` 刻意避开保留字（H2 中 `value` 为保留字）。

行为细节：

- `put(IF_ABSENT)`：先删除同键已过期记录（避免过期数据阻塞 SETNX），再 INSERT，撞 `(space, name)` 唯一索引即返回 false；
- `put(SET)`：先 UPDATE，0 行转 INSERT，并发冲突再回退 UPDATE（经典 upsert）；
- CAS：`UPDATE ... WHERE ... AND kv_value = ? AND 未过期` / 同型 DELETE，行锁保证原子；
- `get` 读到过期行顺手删除（惰性清理）；`pruneExpired` 按 `LIMIT maxBatch` 分批删除；
- 值长度上限 4000 字符（表结构决定），更大值请使用 Redis 后端或自行扩列。

## Redis：RedisKvStore

基于 `StringRedisTemplate`，物理键为 `space:key`（与 `SpaceKey.toString()` 一致）：

```java
KvStore kv = new RedisKvStore(stringRedisTemplate);

// 带键前缀（多应用共用 Redis 时隔离）+ 测试时钟
KvStore kv = new RedisKvStore(stringRedisTemplate, "app1:", clock);
```

操作到 Redis 命令的映射：

| 操作 | 映射 |
| :--- | :--- |
| `get` | `GET` + `PTTL`（换算出精确 `expireAt`；PTTL=-1 视为永不过期） |
| `put(SET)` | `SET key value [PX ttl]` |
| `put(IF_ABSENT)` | `SET key value NX [PX ttl]` |
| `remove` | `DEL` |
| `expire` | `PEXPIRE` / `PERSIST`（`ttl<=0` 对齐契约语义） |
| CAS | Lua 脚本：`GET` 值匹配则 `SET`/`DEL`，单脚本原子 |
| `scan` | `SCAN MATCH space:*`（游标遍历，不用阻塞的 `KEYS`） |

要点：

- 实现 `NativeTtlCapable`：过期由 Redis 淘汰，`pruneExpired` 恒返回 0，清理器自动跳过；
- 键前缀会同时作用于物理键与 `scan` 的匹配模式；
- 值为原样字符串（不做 JSON 包装），与内存/JDBC 实现存储格式一致。

## 自定义后端

实现 `KvStore` 四操作 + 按能力实现可选接口即可（如 MongoDB、本地 Caffeine）：

```java
public class MongoKvStore implements KvStore, CasCapable, ScanCapable {
    // 四操作必须完整实现过期语义（get 过滤已过期、IF_ABSENT 原子）
    // CasCapable 做不到原子就不实现，锁会在构造期快速失败
}
```

实现完成后继承 [AbstractKvStoreContractTest](kv-test.md) 跑契约测试，保证与既有实现行为一致。
