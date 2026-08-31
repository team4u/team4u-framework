# 序列化避坑指南与最佳实践

在分布式系统、KV 缓存存储与跨进程 RPC 中，JSON 序列化是最常见的故障高发区。本文汇总了使用 `team4u-serializer` 过程中的核心避坑指南与生产排查手册。

---

## 常见序列化反模式与排查

### 1. 缺少默认无参构造函数
- **现象**：反序列化时抛出 `InvalidDefinitionException: Cannot construct instance of ... (no Creators, like default constructor, exist)`；
- **解决**：为所有 DTO 添加无参构造函数（若使用 Lombok，确保添加 `@NoArgsConstructor` 与 `@AllArgsConstructor`）。

### 2. 泛型类型擦除导致反序列化为 `LinkedHashMap`
- **现象**：`JsonUtil.fromJson(json, List.class)` 内部的元素变成了 `LinkedHashMap` 而非预期的领域实体；
- **解决**：使用 `TypeReference` 传递完整的泛型签名：
  ```java
  List<UserDto> users = JsonUtil.fromJson(json, new TypeReference<List<UserDto>>() {});
  ```

### 3. Map 键顺序不确定性破坏散列比对
- **现象**：在持久化快照（Durable Snapshot）比对中，内容相同的 Map 输出的 JSON 字符串字节散列不同；
- **解决**：开启 `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`，确保 Map 序列化时按键名字母序输出。

### 4. 循环引用导致堆栈溢出 (`StackOverflowError`)
- **现象**：双向关联的实体（如 `Order -> OrderItem -> Order`）序列化时死循环；
- **解决**：在子关联字段上标注 `@JsonIgnore` 或 `@JsonBackReference`。

---

## 关联章节与进一步阅读

- 了解 JsonUtil 门面：[JsonUtil 统一门面与常用操作](serializer-facade.md)
- 了解模块贡献 SPI：[自定义 JacksonModuleContributor SPI](serializer-custom.md)
- 探索实战案例：[序列化组件实战案例](serializer-sample.md)
