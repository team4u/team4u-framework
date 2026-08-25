# 统一门面与泛型解析 (JsonUtil)

`JsonUtil` 是业务与 SDK 直接使用的统一门面静态工具类。在类加载时，它会自动通过 `PolicyScanner` 扫描当前类路径下可用的 `JsonSerializerPolicy` 实现，并选择优先级最高且支持当前运行环境的策略作为底层引擎。

---

## 核心 API 清单

### 1. 序列化
```java
public static String toJsonStr(Object obj);
```
- 将任意 Java 对象转换为 JSON 字符串；
- 若入参为 `null`，直接返回 `null`。

---

### 2. 简单反序列化
```java
public static <T> T toBean(String json, Class<T> clazz);
```
- 将 JSON 字符串反序列化为指定类型的 JavaBean 实例；
- 若 JSON 为空串或 `null`，返回 `null`。

---

### 3. 反射类型反序列化
```java
public static <T> T toBean(String json, Type type);
```
- 接收 `java.lang.reflect.Type`，支持动态反射或通过泛型上下文推导的复杂类型反序列化。

---

### 4. 强类型泛型标记反序列化 (`TypeReference`)
```java
public static <T> T toBean(String json, TypeReference<T> typeReference);
```
- 通过匿名内部类捕获完整的泛型信息（如 `new TypeReference<Result<List<UserDTO>>>() {}`）。

---

### 5. 容错解析模式 (`ignoreError`)
```java
public static <T> T toBean(String json, TypeReference<T> typeReference, boolean ignoreError);
```
- 当 `ignoreError` 为 `true` 时，若 JSON 报文格式不合法、属性类型不匹配或反序列化过程抛出任何异常，方法会自动捕获并安全返回 `null`，不向外抛出异常；
- 常用于在配置中心或异步队列中读取可能存在历史脏数据的可选配置。

---

### 6. 便捷列表反序列化
```java
public static <T> List<T> toList(String json, Class<T> clazz);
```
- 将 JSON 数组字符串直接转换为指定泛型元素的 `List<T>` 集合。

---

### 7. 通用对象树解析
```java
public static Object parseObj(String json);
```
- 将 JSON 字符串解析为通用树状结构（例如在 Jackson 驱动下返回 `com.fasterxml.jackson.databind.JsonNode` 实例），便于动态遍历未建模的复杂 JSON。

---

### 8. 获取当前活跃策略
```java
public static JsonSerializerPolicy getPolicy();
```
- 获取当前选中的底层序列化策略实例；
- 若类路径下未引入任何 `JsonSerializerPolicy` 实现，抛出 `IllegalStateException`。
