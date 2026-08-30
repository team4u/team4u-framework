# 通用轻量缓存体系

为了避免框架底层对 Guava 或 Caffeine 产生强依赖，`team4u-base` 内置了一套纯 Java 实现的高性能、线程安全的内存缓存体系。

---

## 缓存接口抽象 (`Cache<K, V>`)

```java
package com.team4u.framework.base.cache;

public interface Cache<K, V> {
    /** 获取缓存值，不存在或已过期返回 null */
    V get(K key);

    /** 写入缓存项 */
    void put(K key, V value);

    /** 移除指定的缓存项 */
    void remove(K key);

    /** 清空所有缓存内容 */
    void clear();

    /** 获取当前有效缓存条目总数 */
    int size();
}
```

---

## 核心缓存实现一览

| 缓存实现 | 淘汰机制 | 线程安全设计 | 适用场景 |
| :--- | :--- | :--- | :--- |
| `LRUCache<K, V>` | 最近最少使用淘汰 (Least Recently Used) | `synchronized` 保护 | 固定容量下的通用高频热点读写缓存 |
| `LFUCache<K, V>` | 最少访问频次淘汰 (Least Frequently Used) | `ReentrantLock` 保护 | 针对访问频次具备长期倾斜性的场景（如单例池、规则匹配） |
| `TimedCache<K, V>` | TTL 存活时长过期淘汰 | 惰性检查 + `ConcurrentHashMap` | 临时 Token、动态验证码、限流计数器、限时缓存 |

---

## 各缓存实现细节与用法

### `LRUCache<K, V>`
基于 `LinkedHashMap(capacity, 0.75f, true)` 实现，重写 `removeEldestEntry` 实现容量超限时自动淘汰最久未访问的条目。
```java
import com.team4u.framework.base.cache.Cache;
import com.team4u.framework.base.cache.CacheUtil;

// 创建容量为 1000 的 LRU 缓存
Cache<String, UserInfo> userCache = CacheUtil.newLRUCache(1000);
userCache.put("U101", new UserInfo("Tom"));
UserInfo user = userCache.get("U101");
```

---

### `LFUCache<K, V>`
内部采用多级频次桶（`frequencyBuckets: Map<Integer, LinkedHashSet<K>>`）与最小频次指针 `minFrequency`：
- 访问节点时频次自动递增，并在频次桶间平移；
- 淘汰时以 $O(1)$ 时间复杂度快速定位并移除最小频次桶中最久未访问的条目；
- 内部采用 `ReentrantLock` 保证操作的原子性与线程安全。
```java
Cache<String, RulePolicy> policyCache = CacheUtil.newLFUCache(500);
policyCache.put("rule_order", new RulePolicy());
```

---

### `TimedCache<K, V>`
- 基于 `ConcurrentHashMap<K, CacheObj<V>>` 实现；
- 每个条目包装过期时间点 `expireTime = now + timeout`；
- 采用**惰性删除（Lazy Expiration）**策略：在 `get(key)` 时检查若已过期则原子删除并返回 `null`；在调用 `size()` 时全量清理已过期的条目。

#### 原子懒加载 `getOrCreate` 方法
`TimedCache` 提供了基于 `ConcurrentHashMap.compute` 实现的原子查询或创建能力，彻底杜绝多线程并发穿透：
```java
import com.team4u.framework.base.cache.TimedCache;
import com.team4u.framework.base.cache.CacheUtil;

// 构造默认 TTL 为 60 秒的 TimedCache
TimedCache<String, TokenInfo> tokenCache = CacheUtil.newTimedCache(60_000L);

// 原子获取；若不存在或已过期，执行 Supplier 并原子存入
TokenInfo token = tokenCache.getOrCreate("client_app_01", () -> {
    return fetchRemoteToken("client_app_01");
});
```

---

## 工厂工具类 (`CacheUtil`)

```java
package com.team4u.framework.base.cache;

public final class CacheUtil {
    /** 创建 LRU 缓存 */
    public static <K, V> Cache<K, V> newLRUCache(int capacity);

    /** 创建 LFU 缓存 */
    public static <K, V> Cache<K, V> newLFUCache(int capacity);

    /** 创建具有统一超时时长的 TimedCache */
    public static <K, V> TimedCache<K, V> newTimedCache(long timeout);
}
```
