# SPI 扩展与引擎替换

如果你的应用体系需要使用 FastJSON2、Gson 或自研高性能序列化库替换默认的 Jackson，只需实现 `JsonSerializerPolicy` 接口并通过 SPI 进行声明。

---

## `JsonSerializerPolicy` 接口定义

```java
package com.team4u.framework.serializer.json;

import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.policy.api.KeyedPolicy;

import java.lang.reflect.Type;
import java.util.List;

public interface JsonSerializerPolicy extends ContextPolicy<Void>, KeyedPolicy<String> {

    /** 将对象转换为 JSON 字符串 */
    String toJsonStr(Object obj);

    /** 将 JSON 字符串转换为指定 Class 对象 */
    <T> T toBean(String json, Class<T> clazz);

    /** 将 JSON 字符串转换为复杂泛型 Type 对象 */
    <T> T toBean(String json, Type type);

    /** 将 JSON 字符串转换为 List 集合 */
    <T> List<T> toList(String json, Class<T> clazz);

    /** 解析为通用树对象 */
    Object parseObj(String json);
}
```

---

## 编写自定义策略实现（以 FastJSON2 为例）

```java
package com.mycompany.serializer;

import com.alibaba.fastjson2.JSON;
import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.serializer.json.JsonSerializerPolicy;

import java.lang.reflect.Type;
import java.util.List;

public class FastJson2SerializerPolicy implements JsonSerializerPolicy {

    @Override
    public String toJsonStr(Object obj) {
        if (obj == null) return null;
        return JSON.toJSONString(obj);
    }

    @Override
    public <T> T toBean(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return null;
        return JSON.parseObject(json, clazz);
    }

    @Override
    public <T> T toBean(String json, Type type) {
        if (json == null || json.isEmpty()) return null;
        return JSON.parseObject(json, type);
    }

    @Override
    public <T> List<T> toList(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return null;
        return JSON.parseArray(json, clazz);
    }

    @Override
    public Object parseObj(String json) {
        if (json == null || json.isEmpty()) return null;
        return JSON.parse(json);
    }

    @Override
    public boolean supports(Void context) {
        try {
            Class.forName("com.alibaba.fastjson2.JSON");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int priority() {
        // 设置比 Jackson (HIGH=0) 更高的优先级（数值更小）
        return -10;
    }

    @Override
    public String key() {
        return "fastjson2";
    }
}
```

---

## 注册 SPI 配置文件

在 `resources/META-INF/services/com.team4u.framework.serializer.json.JsonSerializerPolicy` 中写入实现类的全限定名：

```text
com.mycompany.serializer.FastJson2SerializerPolicy
```

`JsonUtil` 会通过 `PolicyScanner` 自动扫描该实现并根据 `priority()` 优先挂载。全工程的所有 `JsonUtil.toJsonStr(...)` 与 `JsonUtil.toBean(...)` 调用将无缝切换至 FastJSON2 引擎！
