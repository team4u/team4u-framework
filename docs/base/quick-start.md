# 快速开始

本文介绍如何在项目中引入并使用 `team4u-base` 提供的基础核心工具。

---

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-base</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## 文本模板渲染 (`TextTemplate`)

```java
import com.team4u.framework.base.util.TextTemplate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// 1. 预解析模板（建议在初始化时创建并静态复用）
TextTemplate template = new TextTemplate("biz.${region}.${tenantId}.router");

// 2. 提取变量名列表（保持出现顺序）
Set<String> vars = template.getVariableNames(); // ["region", "tenantId"]

// 3. 传入上下文 Map 极速渲染（零正则开销）
Map<String, Object> context = new HashMap<>();
context.put("region", "shanghai");
context.put("tenantId", "alipay");

String result = template.render(context);
System.out.println(result); // biz.shanghai.alipay.router
```

---

## 全局反射单例工厂 (`SingletonFactory`)

```java
import com.team4u.framework.base.instance.SingletonFactory;

// 线程安全且基于 LFU 缓存的单例获取（首次调用时反射创建并全局缓存）
OrderPaymentService service = SingletonFactory.getInstance(OrderPaymentService.class);
```

---

## 通用类型转换 (`ConvertUtil`)

```java
import com.team4u.framework.base.convert.ConvertUtil;
import java.time.LocalDate;
import java.util.List;

// 1. 标量类型安全转换（参数顺序：目标类型在前，源数据在后）
int port = ConvertUtil.convert(Integer.class, "8080");
boolean enabled = ConvertUtil.toBool("true"); // 支持 1/yes/on/true

// 2. 日期转换
LocalDate date = ConvertUtil.convert(LocalDate.class, "2026-08-25");

// 3. 集合转换（自动按逗号拆分）
List<String> tags = ConvertUtil.toList("apple,banana,orange");
```

---

## 本地缓存工具 (`CacheUtil`)

```java
import com.team4u.framework.base.cache.Cache;
import com.team4u.framework.base.cache.CacheUtil;
import com.team4u.framework.base.cache.TimedCache;

// 创建容量为 1000 的 LRU 缓存
Cache<String, Object> lruCache = CacheUtil.newLRUCache(1000);

// 创建过期时间为 60 秒的 TimedCache
TimedCache<String, String> tokenCache = CacheUtil.newTimedCache(60_000L);
String token = tokenCache.getOrCreate("user_1001", () -> "TOKEN_ABC_999");
```

---

## 流式 SQL 构造 (`InsertBuilder` / `JdbcUtil`)

```java
import com.team4u.framework.base.jdbc.InsertBuilder;
import com.team4u.framework.base.jdbc.JdbcUtil;

// 流式构建 INSERT INTO 语句
InsertBuilder insertBuilder = new InsertBuilder("system_config")
        .column("config_type", "router")
        .column("config_key", "order-router")
        .column("config_value", "{...}")
        .columnIfNotNull("remark", null); // 为 null 自动跳过

// 执行插入
JdbcUtil.execute(dataSource, insertBuilder.getSql(), insertBuilder.getParams());
```

---

## 下一步

- 深入分段锁与双缓存流水线：[动态实例与单例工厂](base-instance.md)
- 探索高性能预解析模板引擎：[文本模板解析器 (TextTemplate)](base-template.md)
- 查看本地缓存体系与淘汰策略：[通用轻量缓存体系](base-cache.md)
- 深入强类型转换器注册表：[类型转换器体系 (ConvertUtil)](base-convert.md)
- 了解流式 SQL 构造器与极简 CRUD：[极简 JDBC 构建工具 (JdbcUtil)](base-jdbc.md)
