[返回总目录](../README.md)

# 契约翻译模块 (team4u-translator)

[![JDK 8+](https://img.shields.io/badge/JDK-8+-green.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

## 目录

- [简介](#简介)
- [核心模型](#核心模型)
- [工作流程](#工作流程)
- [快速开始](#快速开始)
- [进阶示例](#进阶示例)
- [扩展渲染器](#扩展渲染器)
- [当前约束与说明](#当前约束与说明)

---

## 简介

`team4u-translator` 用于把内部系统返回的原始响应 `RawResponse`，按路由规则翻译成对外暴露的统一结果 `TranslatedResponse`。

它适合放在系统边界层使用，例如：

- 将内部异常码映射成统一的业务错误码
- 将内部报错文案替换成用户可读的提示信息
- 在输出文案中插入上下文变量，如 `traceId`、业务动作、请求参数摘要

模块本身只负责“翻译”这件事，核心依赖：

- [team4u-router](../team4u-router/README.md)：根据 `routerId` 找到命中的 `ErrorDef`
- [team4u-policy](../team4u-policy/README.md)：按优先级执行渲染策略链

---

## 核心模型

### `ResponseTranslator`

统一入口接口：

```java
TranslatedResponse translate(RawResponse source, String routerId, Map<String, Object> args);
```

- `source`：待翻译的原始响应
- `routerId`：路由规则标识
- `args`：本次翻译的动态参数，例如 `traceId`、模板变量等

### `RawResponse`

表示上游系统的原始输出，包含：

- `domain`：来源域或来源系统
- `code`：原始错误码
- `message`：原始消息
- `cause`：可选异常对象

### `ErrorDef`

路由命中后返回的目标定义，当前包含：

- `code`：翻译后的目标错误码
- `defaultMsg`：默认文案模板
- `logLevel`：扩展字段，当前默认引擎不消费

### `TranslatedResponse`

最终输出结果，包含：

- `code`
- `message`
- `traceId`

### `RenderPolicy`

渲染策略 SPI。内置实现包括：

- `TemplateRenderPolicy`
- `FallbackRenderPolicy`

---

## 工作流程

一次翻译的执行过程如下：

1. 调用 `ResponseTranslator.translate(...)`
2. 用 `RawResponse` 和 `args` 构造路由上下文
3. 通过 `RoutingManager` 按 `routerId` 查找匹配的 `ErrorDef`
4. 若未命中路由，直接返回原始 `code` 和 `message`
5. 若命中路由，构造 `RenderContext`
6. 依次执行命中的 `RenderPolicy`
7. 输出 `TranslatedResponse`

从当前源码行为看，几个关键点需要特别注意：

- `traceId` 只从 `args.get("traceId")` 提取
- `source` 不能为空，为 `null` 时会抛出 `NullPointerException`
- 模板渲染会自动注入 `rawCode` 和 `rawMessage`
- 当目标 `code` 或 `message` 为空时，兜底策略会回填原始值
- 默认只通过 SPI 注册 `RenderPolicy`；包扫描需要显式启用

---

## 快速开始

### 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-translator</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 发起一次翻译

```java
import com.team4u.framework.translator.api.ResponseTranslator;
import com.team4u.framework.translator.engine.DefaultResponseTranslator;
import com.team4u.framework.translator.model.RawResponse;
import com.team4u.framework.translator.model.TranslatedResponse;

import java.util.HashMap;
import java.util.Map;

ResponseTranslator translator = new DefaultResponseTranslator();

RawResponse source = RawResponse.of(
        "ORDER_CENTER",
        "ORDER_001",
        "上游订单服务暂时不可用"
);

Map<String, Object> args = new HashMap<>();
args.put("traceId", "trace-20260314-001");
args.put("action", "提交订单");

TranslatedResponse response = translator.translate(source, "error_router", args);

System.out.println(response.getCode());
System.out.println(response.getMessage());
System.out.println(response.getTraceId());
```

其中：

- `error_router` 是路由规则标识
- 实际路由规则由 `team4u-router` 负责解析与命中
- 命中的目标值需要能转换成 `ErrorDef`

如果需要在默认 SPI 之外显式启用包扫描，可使用：

```java
ResponseTranslator translator = new DefaultResponseTranslator(
        RoutingManager.global(),
        "com.yourcompany.project.translator"
);
```

### 一个基础路由示例

下面的示例展示如何把上游错误翻译成统一契约：

```json
{
  "id": "error_router",
  "type": "expression",
  "rules": [
    {
      "condition": "domain == 'ORDER_CENTER'",
      "value": {
        "code": "ORDER_SERVICE_UNAVAILABLE",
        "defaultMsg": "操作失败：${action}，请稍后重试。原始原因：${rawMessage}"
      }
    }
  ]
}
```

如果当前请求参数中包含：

```java
args.put("action", "提交订单");
```

则最终返回的消息形如：

```text
操作失败：提交订单，请稍后重试。原始原因：上游订单服务暂时不可用
```

---

## 进阶示例

### 1. 组合路由：业务规则优先，全局规则兜底

当某条业务线需要优先覆盖全局翻译规则时，可以借助 `composite` 路由器组合多个路由定义。

业务专用规则：

```json
{
  "id": "translator.biz-order",
  "type": "map",
  "rules": [
    {
      "condition": "PAY_FAIL",
      "value": {
        "code": "ORDER_PAY_FAILED",
        "defaultMsg": "订单支付失败，请检查余额或稍后重试"
      }
    }
  ]
}
```

全局规则：

```json
{
  "id": "translator.system",
  "type": "expression",
  "rules": [
    {
      "condition": "code == 'DB_TIMEOUT'",
      "value": {
        "code": "SYSTEM_BUSY",
        "defaultMsg": "系统繁忙，请稍后再试"
      }
    }
  ],
  "fallbackValue": {
    "code": "UNKNOWN_ERROR",
    "defaultMsg": "系统异常：${rawMessage}"
  }
}
```

组合入口：

```json
{
  "id": "translator.main",
  "type": "composite",
  "ext": {
    "delegates": [
      "translator.biz-order",
      "translator.system"
    ]
  }
}
```

此时可直接调用：

```java
TranslatedResponse response = translator.translate(source, "translator.main", args);
```

### 2. 模板变量说明

模板渲染支持两类变量：

- 调用方透传的 `args`
- 框架自动注入的 `rawCode`、`rawMessage`

示例：

```json
{
  "code": "SYSTEM_ERROR",
  "defaultMsg": "内部异常[${rawCode}]，原因：${rawMessage}。业务操作：${action}"
}
```

如果 `action = 查询明细`，则消息会被渲染为：

```text
内部异常[NPE]，原因：空指针异常。业务操作：查询明细
```

---

## 扩展渲染器

如果内置策略不满足需求，可以实现自定义 `RenderPolicy`，例如在模板渲染后做脱敏处理。

### 编写策略

```java
import com.team4u.framework.translator.model.RenderContext;
import com.team4u.framework.translator.render.RenderPolicy;

public class MyDesensitizationPolicy implements RenderPolicy {

    @Override
    public int priority() {
        return NORMAL + 100;
    }

    @Override
    public boolean supports(RenderContext context) {
        return true;
    }

    @Override
    public void render(RenderContext context) {
        String message = context.getFinalMessage();
        if (message != null && message.contains("138")) {
            context.setFinalMessage(message.replaceAll("138\\d{8}", "138****"));
        }
    }
}
```

### 注册 SPI

在 `src/main/resources/META-INF/services/com.team4u.framework.translator.render.RenderPolicy` 中添加实现类全限定名：

```text
com.yourcompany.project.MyDesensitizationPolicy
```

启动后，`DefaultResponseTranslator` 默认只会通过 SPI 注册可用策略；如需包扫描，需使用带 `scanPackages` 参数的构造器显式开启。

---

## 当前约束与说明

- 当前默认引擎只负责翻译，不内置 Spring AOP、异常拦截或网关封装能力。
- `logLevel` 虽然存在于 `ErrorDef` 中，但默认翻译流程不会使用它。
- 只有当消息中包含 `${...}` 模板占位符时，`TemplateRenderPolicy` 才会参与渲染。
- 当模板变量缺失时，未命中的占位符会保留原样。
- 未命中任何路由规则时，返回值等同于原始输入的 `code` 和 `message`；若 `args` 中传入了 `traceId`，则会原样保留。
