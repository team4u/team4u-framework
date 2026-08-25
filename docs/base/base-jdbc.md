# 极简 JDBC 构建工具 (JdbcUtil)

在开发轻量级扩展插件（如 `team4u-config-db`、`team4u-lease-jdbc`）或独立数据处理任务时，为了避免引入 MyBatis、Hibernate 等重量级 ORM 框架，`team4u-base` 提供了极简的 SQL 构建器与原生 JDBC 执行工具。

---

## 核心构建器一览

### 结构化插入构建器 (`InsertBuilder`)
```java
package com.team4u.framework.base.jdbc;

public class InsertBuilder {
    public InsertBuilder(String tableName);

    /** 设置字段及其值 */
    public InsertBuilder column(String column, Object value);

    /** 当值不为 null 时才设置字段及其值 */
    public InsertBuilder columnIfNotNull(String column, Object value);

    /** 获取生成的 INSERT INTO SQL 字符串 */
    public String getSql();

    /** 获取参数数组 */
    public Object[] getParams();
}
```

---

### 结构化更新构建器 (`UpdateBuilder`)
```java
package com.team4u.framework.base.jdbc;

public class UpdateBuilder {
    public UpdateBuilder(String tableName);

    /** 设置更新字段值 */
    public UpdateBuilder set(String column, Object value);

    /** 设置更新为 SQL 表达式（例如 version = version + 1） */
    public UpdateBuilder setExpression(String column, String expression);

    /** 当值不为 null 时设置更新值 */
    public UpdateBuilder setIfNotNull(String column, Object value);

    /** 追加 WHERE 条件片段及对应参数 */
    public UpdateBuilder where(String condition, Object... args);

    /** 获取生成的 UPDATE SQL 字符串 */
    public String getSql();

    /** 获取合并后的参数数组（SET 参数在前，WHERE 参数在后） */
    public Object[] getParams();
}
```

---

### 动态流式 SQL 构建器 (`SqlBuilder`)
```java
package com.team4u.framework.base.jdbc;

public class SqlBuilder {
    public SqlBuilder();
    public SqlBuilder(String initialSql);

    /** 追加 SQL 片段及参数 */
    public SqlBuilder append(String snippet, Object... args);

    /** 当值不为 null 时追加 SQL 片段及参数 */
    public SqlBuilder appendIfNotNull(String snippet, Object value);

    /** 当集合非空时追加 IN 占位符并展开集合参数 */
    public SqlBuilder inIfNotEmpty(String snippetPrefix, Collection<?> values);

    /** 追加使用括号包裹的 AND 组合条件 */
    public SqlBuilder and(Consumer<SqlBuilder> consumer);

    /** 追加使用括号包裹的 OR 组合条件 */
    public SqlBuilder or(Consumer<SqlBuilder> consumer);

    /** 获取最终 SQL 字符串 */
    public String getSql();

    /** 获取参数数组 */
    public Object[] getParams();
}
```

---

## 原生 JDBC 操作工具 (`JdbcUtil`)

`JdbcUtil` 统一管理数据库连接（`Connection`）与语句对象（`PreparedStatement`）的创建与自动安全关闭（`try-with-resources`）：

| 方法签名 | 说明 |
| :--- | :--- |
| `List<Map<String, Object>> query(DataSource, sql, params...)` | 查询并返回行 Map 列表（列名自动转小写） |
| `Map<String, Object> queryOneMap(DataSource, sql, params...)` | 查询单条记录并返回行 Map，无记录返回 `null` |
| `<T> T queryScalar(DataSource, sql, Class<T> type, params...)` | 查询单个标量值（如 `COUNT(*)`, `MAX(id)`），自动进行类型转换 |
| `<T> List<T> queryList(DataSource, sql, Class<T> clazz, params...)` | 查询多条记录并自动映射为 JavaBean 实体列表（自动支持下划线转驼峰与强类型转换） |
| `<T> T queryOne(DataSource, sql, Class<T> clazz, params...)` | 查询单条记录并映射为 JavaBean 实体 |
| `int insert(DataSource, tableName, Object bean)` | 自动提取 JavaBean 的非空属性转换为下划线列名并执行插入 |
| `Long insertAndReturnKey(DataSource, tableName, Object bean)` | 执行 JavaBean 实体插入并返回生成的自增主键 |
| `Long insertAndReturnKey(DataSource, sql, params...)` | 执行原生插入 SQL 并返回生成的自增主键 |
| `int execute(DataSource, sql, params...)` | 执行 UPDATE, INSERT, DELETE 等更新语句并返回影响行数 |
| `int executeWithBean(DataSource, sql, bean, firstField, otherFields...)` | 提取 JavaBean 指定属性值按顺序绑定占位符并执行 SQL |

---

## 完整使用示例

### 流式更新与插入
```java
import com.team4u.framework.base.jdbc.InsertBuilder;
import com.team4u.framework.base.jdbc.UpdateBuilder;
import com.team4u.framework.base.jdbc.JdbcUtil;

// 1. 流式插入
InsertBuilder insert = new InsertBuilder("t_order")
        .column("order_id", "ORD-1001")
        .column("user_id", "U9988")
        .column("amount", 299.00);

Long generatedId = JdbcUtil.insertAndReturnKey(dataSource, insert.getSql(), insert.getParams());

// 2. 流式更新（带表达式与条件）
UpdateBuilder update = new UpdateBuilder("t_order")
        .set("status", "PAID")
        .setExpression("version", "version + 1")
        .where("order_id = ?", "ORD-1001")
        .where("version = ?", 1);

int rows = JdbcUtil.execute(dataSource, update.getSql(), update.getParams());
```

---

### 动态多条件查询与实体映射
```java
import com.team4u.framework.base.jdbc.SqlBuilder;
import com.team4u.framework.base.jdbc.JdbcUtil;
import java.util.List;

public class OrderQueryService {

    public List<OrderVO> queryOrders(String status, List<String> userIds) {
        SqlBuilder sql = new SqlBuilder("SELECT * FROM t_order WHERE 1=1");
        
        sql.appendIfNotNull(" AND status = ?", status);
        sql.inIfNotEmpty(" AND user_id IN ", userIds);
        sql.append(" ORDER BY create_time DESC");

        // 自动完成 下划线列名 -> 实体驼峰属性 的映射与 ConvertUtil 类型转换
        return JdbcUtil.queryList(dataSource, sql.getSql(), OrderVO.class, sql.getParams());
    }
}
```
