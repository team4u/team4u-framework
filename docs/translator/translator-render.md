# 模板渲染与策略扩展

`team4u-translator` 内置了高性能变量模板渲染与字段兜底填充策略，并基于 `team4u-policy` 的 `ContextPolicy` 提供了灵活的责任链扩展机制。

---

## 渲染管线执行架构

```mermaid
graph LR
    RC[RenderContext<br/>finalCode, finalMessage] --> P1[1. TemplateRenderPolicy<br/>priority=NORMAL(0)<br/>${...} 占位符解析替换]
    P1 --> P2[2. 自定义业务策略<br/>priority=NORMAL+10<br/>敏感词脱敏 / 多语言翻译]
    P2 --> P3[3. FallbackRenderPolicy<br/>priority=LOWEST<br/>空值字段兜底回填]
    P3 --> Out[TranslatedResponse]
```

---

## 1. 内置策略详解

### `TemplateRenderPolicy` (变量模板渲染器)
- **优先级**：`priority() = NORMAL (0)`
- **触发条件**：`finalMessage` 非空且包含 `${` 标识。
- **模板缓存**：内部维护容量为 256 的 LRU 缓存池（`TEMPLATE_CACHE`），避免频繁解析相同模板字符串。
- **自动注入内置变量**：
  - `${rawCode}`：上游原始错误码（对应 `source.getCode()`）。
  - `${rawMessage}`：上游原始错误描述（对应 `source.getMessage()`）。
- **合并请求变量**：自动合并外部 `args` 传入的所有业务参数。
- **宽容容错**：未在 `args` 中提供的占位符将**保留原样**（如 `${unknownVar}`），不会抛出异常。

#### 模板规则示例：
```json
{
  "code": "DB_LOCK_TIMEOUT",
  "defaultMsg": "操作【${action}】失败：系统繁忙[${rawCode}]，原因：${rawMessage}，流水号：${bizNo}"
}
```

---

### `FallbackRenderPolicy` (兜底安全渲染器)
- **优先级**：`priority() = LOWEST (Integer.MAX_VALUE)`，确保在管线最后一步执行。
- **触发条件**：始终匹配 (`supports = true`)。
- **回填机制**：
  - 若 `finalCode` 为空或 `null`，自动使用 `source.getCode()` 回填。
  - 若 `finalMessage` 为空或 `null`，自动使用 `source.getMessage()` 回填。

> [!IMPORTANT]
> **防变量注入安全设计**：由于 `TemplateRenderPolicy` 优先于 `FallbackRenderPolicy` 执行，当路由静态规则未配置 `defaultMsg` 时，由 Fallback 策略回填的原始异常文本（即使内容中包含 `${...}` 字符）**绝对不会被二次当成模板渲染**，从而杜绝了原始数据污染与潜在的模板注入风险。

---

## 2. 编写自定义渲染策略 (`RenderPolicy`)

开发者可以实现 `RenderPolicy` 接口，在消息最终输出前执行敏感词脱敏、多语言国际化转换或动态安全审计。

### 示例 1：敏感词与手机号脱敏渲染器
```java
package com.mycompany.translator.render;

import com.team4u.framework.translator.model.RenderContext;
import com.team4u.framework.translator.render.RenderPolicy;

public class DataMaskingRenderPolicy implements RenderPolicy {

    @Override
    public int priority() {
        return NORMAL + 10; // 介于 Template 与 Fallback 之间
    }

    @Override
    public boolean supports(RenderContext context) {
        return context.getFinalMessage() != null;
    }

    @Override
    public void render(RenderContext context) {
        String msg = context.getFinalMessage();
        // 手机号脱敏正则替换
        String masked = msg.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
        context.setFinalMessage(masked);
    }
}
```

### 示例 2：多语言国际化渲染器 (i18n)
```java
package com.mycompany.translator.render;

import com.team4u.framework.translator.model.RenderContext;
import com.team4u.framework.translator.render.RenderPolicy;

public class I18nRenderPolicy implements RenderPolicy {

    @Override
    public int priority() {
        return NORMAL + 20;
    }

    @Override
    public boolean supports(RenderContext context) {
        // 仅当请求参数中指定了 lang 时触发
        return context.getArgs().containsKey("lang");
    }

    @Override
    public void render(RenderContext context) {
        String lang = String.valueOf(context.getArgs().get("lang"));
        String code = context.getFinalCode();
        
        // 从国际化资源包中按 code 和 lang 提取翻译文案
        String localizedMsg = MessageSource.getMessage(code, lang);
        if (localizedMsg != null) {
            context.setFinalMessage(localizedMsg);
        }
    }
}
```

---

## 3. 注册渲染策略

### 方式 A：Java SPI 自动加载（推荐）
在 `META-INF/services/com.team4u.framework.translator.render.RenderPolicy` 文件中追加实现类的全限定名：
```text
com.mycompany.translator.render.DataMaskingRenderPolicy
com.mycompany.translator.render.I18nRenderPolicy
```

### 方式 B：构造器指定包路径扫描
在初始化 `DefaultResponseTranslator` 时传入待扫描的包路径：
```java
ResponseTranslator translator = new DefaultResponseTranslator(
        "com.mycompany.translator.render"
);
```
框架内部将自动扫描并按 `priority()` 排序接入渲染管线。
