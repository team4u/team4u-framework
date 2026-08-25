# 文本模板解析器 (TextTemplate)

`TextTemplate` 是一个专为高性能路由键生成、动态配置格式化与通知消息模板渲染设计的通用占位符引擎。

---

## 核心设计原理

- **“预解析 + 运行时拼接”架构**：在构造 `TextTemplate` 实例时，通过正则表达式 `\$\{(.+?)\}` 一次性将输入字符串预先拆分为静态片段（`LiteralSegment`）与动态占位符片段（`PlaceholderSegment`）。
- **零正则开销**：在实际执行渲染（`render`）时，仅需依次遍历片段列表并向 `StringBuilder` 追加字符，彻底规避了高并发场景下动态正则匹配带来的 CPU 消耗与 GC 开销。
- **变量自发现**：自动提取模板中定义的所有变量名，并保存在 `LinkedHashSet` 中以维持在模板中首次出现的顺序。

---

## 核心 API 清单

```java
package com.team4u.framework.base.util;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class TextTemplate {

    /** 构造文本模板并预解析片段 */
    public TextTemplate(String template);

    /** 判断是否包含动态占位符 */
    public boolean isDynamic();

    /** 获取模板中定义的所有变量名（保持出现顺序且不可修改） */
    public Set<String> getVariableNames();

    /** 通过 Map 上下文渲染模板 */
    public String render(Map<String, ?> context);

    /** 通过自定义值提供者函数渲染模板 */
    public String render(Function<String, Object> valueProvider);

    /** 获取原始模板字符串 */
    public String toString();
}
```

---

## 使用示例

### 1. 基础 Map 渲染
```java
import com.team4u.framework.base.util.TextTemplate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// 1. 初始化模板（建议作为常量或单例复用）
TextTemplate template = new TextTemplate("kafka.topic.${bizType}.${region}.v1");

// 2. 提取变量名列表
Set<String> variableNames = template.getVariableNames();
// 输出: ["bizType", "region"]

// 3. 通过 Map 渲染
Map<String, Object> params = new HashMap<>();
params.put("bizType", "order");
params.put("region", "cn-north");

String topic = template.render(params); 
System.out.println(topic); // "kafka.topic.order.cn-north.v1"
```

---

### 2. 函数式提供者渲染 (`Function<String, Object>`)

当变量值来源于系统属性、环境变量或 Spring `Environment` 时，可以直接传入函数式接口：

```java
// 动态桥接 System.getProperty，未定义时兜底
String dynamicPath = template.render(varName -> {
    if ("region".equals(varName)) {
        return System.getProperty("app.region", "default-region");
    }
    return "test-" + varName;
});
```

---

### 3. 未命中占位符与默认值表现
当渲染上下文（Map 或 Function）中未提供某个变量的值（返回 `null`）时：
`PlaceholderSegment` 会保留原始占位符字符串（如 `${region}`），防止意外抹除未匹配的模板字段。
