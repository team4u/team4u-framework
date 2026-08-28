# 快速开始

本文介绍如何在项目中引入并使用 `team4u-serializer`。

---

## 引入依赖

推荐在项目中引入 `team4u-serializer-jackson` 模块（会自动传递引入核心门面 `team4u-serializer-json`）：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-serializer-jackson</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

> [!TIP]
> 如果你在开发通用 SDK 并不希望强绑定 Jackson，你的 SDK 仅需引入抽象模块 `team4u-serializer-json`，把具体的驱动实现交由最终业务宿主工程决定。宿主工程必须添加 `com.team4u:team4u-serializer-jackson`，或在 `META-INF/services/com.team4u.framework.serializer.json.JsonSerializerPolicy` 中注册自定义实现；否则第一次真实 JSON 转换会抛出 `IllegalStateException` 并给出这两个选择。

---

## 基础对象序列化与反序列化

```java
import com.team4u.framework.serializer.json.JsonUtil;

public class SerializerQuickStart {

    public static class User {
        private String name;
        private Integer age;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }
    }

    public static void main(String[] args) {
        User user = new User();
        user.setName("Jay");
        user.setAge(18);

        // 1. 对象转 JSON 字符串
        String json = JsonUtil.toJsonStr(user);
        System.out.println(json); // {"name":"Jay","age":18}

        // 2. JSON 字符串转 JavaBean
        User decoded = JsonUtil.toBean(json, User.class);
        System.out.println(decoded.getName()); // Jay
    }
}
```

---

## 复杂嵌套泛型反序列化 (`TypeReference`)

借助 `TypeReference`，在运行期完整保留多层泛型类型元数据：

```java
import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.serializer.json.JsonUtil;
import java.util.List;
import java.util.Map;

public class GenericQuickStart {

    public static void main(String[] args) {
        String json = "[{\"userId\":\"U1001\",\"tags\":[\"VIP\",\"AUTH\"]}]";

        // 反序列化为 List<Map<String, Object>>
        List<Map<String, Object>> list = JsonUtil.toBean(
                json, 
                new TypeReference<List<Map<String, Object>>>() {}
        );

        System.out.println(list.get(0).get("userId")); // U1001
    }
}
```

---

## 集合与通用树对象解析

```java
// 快速解析为 List 集合
List<User> users = JsonUtil.toList("[{\"name\":\"A\"},{\"name\":\"B\"}]", User.class);

// 解析为底层树状对象（在 Jackson 下为 JsonNode 实例）
Object jsonTree = JsonUtil.parseObj("{\"code\":200,\"data\":{\"status\":\"OK\"}}");
```

---

## 下一步

- 深入门面方法与容错模式：[统一门面与泛型解析 (JsonUtil)](serializer-facade.md)
- 了解 Jackson 驱动配置与时间格式：[Jackson 驱动与性能优化](serializer-jackson.md)
- 扩展自定义 FastJSON / Gson 策略：[SPI 扩展与引擎替换](serializer-spi.md)
