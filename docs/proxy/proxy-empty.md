# 空对象模式防 NPE (asEmptyObject)

在深层嵌套对象导航（例如分层配置、DTO 报文或外部返回数据）中，繁琐的 `if (a != null && a.getB() != null && a.getB().getC() != null)` 容易导致业务代码充斥大量防御性判空，稍有疏忽便会抛出 `NullPointerException`。

`team4u-proxy` 提供了基于空对象模式（Null Object Pattern）的防御机制，通过代理接管对象链，实现“**零判空、永不抛出 NPE**”的安全调用。

---

## 启用空对象代理 (`asEmptyObject`)

```java
import com.team4u.framework.proxy.ProxyBuilder;

public class AppConfig {
    public DatabaseConfig getDb() {
        return null; // 假设数据源配置未初始化
    }
}

public class DatabaseConfig {
    public String getUrl() {
        return "jdbc:mysql://localhost:3306/db";
    }
    public int getMaxPoolSize() {
        return 100;
    }
}

// 开启空对象模式构建安全代理
AppConfig safeConfig = ProxyBuilder.forClass(AppConfig.class)
        .asEmptyObject()
        .build();

// 即使 getDb() 返回 null，也不会抛出 NPE！
// safeConfig.getDb() 自动返回嵌套的空代理对象：
// 其 getUrl() 安全返回 ""，getMaxPoolSize() 安全返回 0
String url = safeConfig.getDb().getUrl();
int maxPoolSize = safeConfig.getDb().getMaxPoolSize();

System.out.println("安全 URL: '" + url + "'"); // 输出: ''
System.out.println("安全 PoolSize: " + maxPoolSize); // 输出: 0
```

---

## 安全默认值解析规则 (`EmptyValueInterceptor`)

当调用空对象代理的方法时，`EmptyValueInterceptor` 按照以下规则解析并返回安全值：

| 返回值类型 | 解析策略 / 安全返回值 |
| :--- | :--- |
| `void` | `null` |
| `String` | 空字符串 "" |
| `Optional<T>` | `Optional.empty()` |
| `List<T>` | `Collections.emptyList()` |
| `Set<T>` | `Collections.emptySet()` |
| `Map<K, V>` | `Collections.emptyMap()` |
| 数组类型 (`T[]`) | 长度为 0 的同类型数组 `Array.newInstance(componentType, 0)` |
| `boolean / Boolean` | `false` |
| `byte / short / int / long / float / double` | `0` / `0L` / `0.0f` / `0.0d` |
| `char` | `'\0'` |
| 自定义复合对象 | 从全局单例缓存池中获取或动态创建该类型的嵌套空对象代理 |

---

## 递归安全与全局单例池

在深度链式调用或存在自引用类型的复杂对象中，如果每次访问都动态生成新的代理对象，极易引发内存泄漏或 OOM。

`EmptyValueInterceptor` 内部维护了全局单例池：
```java
private static final ConcurrentMap<Class<?>, Object> EMPTY_INSTANCE_CACHE = new ConcurrentHashMap<>();
```
- 每种类型的空代理对象在 JVM 生命周期内**只会被实例化一次**；
- 当调用发生自循环（如 `Node.getNext().getNext()`）时，始终复用单例池中的同一个代理实例，兼顾极限性能与内存安全。

### 基础 Object 方法拦截
为了防止在日志打印或集合比对时发生递归循环或异常，空对象拦截器对基础方法进行了特殊处理：
- `toString()`：返回 "EmptyProxy[" + targetType.getSimpleName() + "]"；
- `hashCode()`：返回 `System.identityHashCode(proxy)`；
- `equals(other)`：仅当 `proxy == arguments[0]` 时返回 `true`。
