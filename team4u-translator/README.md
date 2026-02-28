[返回总目录](../README.md)

# 契约翻译引擎模块 (team4u-translator)

[![JDK 8+](https://img.shields.io/badge/JDK-8+-green.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

## 目录

- [简介](#简介)
- [核心机制](#核心机制)
- [快速入门](#快速入门)
- [执行管线原理](#执行管线原理)
- [扩展渲染器](#扩展渲染器)

---

## 简介

`team4u-translator` 是一个以现代分布式架构、领域驱动设计（DDD）以及高可用微服务治理视角为基础构建的**系统边界契约防腐与转化网关**。

在现代架构中，内部微服务拥有独立的异常或状态码体系，而对外的输出（如 App、第三方系统）必须统一控制。本模块利用团队已有生态（极速路由、策略链、动态代理等），彻底重塑了转换方案，达到了“零侵入与解耦”的设计原则。

### 核心优势

1. **彻底无侵入 (Zero Intrusion)**：配合 Spring AOP 或全局异常拦截网关，业务代码只负责正常的异常抛出，翻译转换动作被透明挂载于框架层。
2. **读写分离与不可变性**：全局配置 (`ErrorDef`) 为只读引用，引擎每次计算均产出崭新的不可变结果对象 (`TranslatedResponse`)，杜绝并发污染。
3. **管线分离与动态渲染**：基于 `team4u-policy` 提供有序责任链支持，内置国际化、变量替换与智能兜底三大核心管线节点，职责各自独立。
4. **高层治理**：支持链路追踪 (TraceId)、日志级别动态下发 (LogLevel) 等高阶配置，为企业的可观测性打通最后一公里。

---

## 核心机制

本模块的核心运转依然坚持**三段式流水线架构**：

1. **上下文组装 (Context Build)**：将被翻译的异常或原始数据 `RawResponse`，连同追踪 ID 等信息组装进 `MatchContext` 执行沙箱中。
2. **极速路由决策 (Routing Target)**：通过给定的 Router ID 向 `RoutingManager` 寻址，获取最佳的静态映射规则节点（`ErrorDef` 实例）。
   *   **组合扩展**：支持使用 `composite` 类型路由器。你可以将“业务定制翻译规则”与“全局通用翻译规则”通过 ID 关联进行串联。引擎会优先尝试匹配业务私有规则，若无匹配则平滑降级至通用规则，实现翻译策略的层级复用。
3. **责任链渲染 (Pipeline Render)**：组装出 `RenderContext` 后加载流转管道。依次通过**兜底 -> 国际化 -> 模板变量替换**的三道工序，最终构建出带追踪戳的 `TranslatedResponse` 交付给最终触点。

---

## 快速入门

### 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-translator</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 发起翻译

```java
// 1. 获取全局唯一的翻译门面
ResponseTranslator translator = new DefaultResponseTranslator();

// 2. 模拟某次微服务异常返回：原始响应对象
RawResponse request = RawResponse.of("ORDER_CENTER", "ORDER_001", "上层订单服务调用失联"); 

// 3. 构建该次拦截或诊断需要的外部附加透传参数
Map<String, Object> additionalArgs = new HashMap<>();
additionalArgs.put("traceId", "acbsad-213csa");

// 4. 发起转换
// "error_router" 是配置中心/路由中心创建的专门路由映射标识（对应 json 例如：router.error_router）
TranslatedResponse response = translator.translate(request, "error_router", additionalArgs);

// 5. 使用或展示最终转换后的契约数据
System.out.println("给用户展示的码: " + response.getCode());
System.out.println("给用户展示的文案: " + response.getMessage());
System.out.println("该异常的链路追踪标识: " + response.getTraceId());
```

---

## 执行管线原理

### 配置的对应关系
当上层经过 `translator.translate` 并顺利通过底层 `RoutingManager` 时，对应的静态 `ErrorDef` 定义会被提取。

**路由定义 (JSON示例) :**
```json
{
  "id": "error_router",
  "type": "expression",
  "rules": [
    {
      "condition": "domain == 'ORDER_CENTER'",
      "value": {
          "code": "G_SYSTEM_DOWN",
          "i18nKey": "err.order.timeout",
          "logLevel": "WARN",
          "defaultMsg": "当前网络开小差了系统反馈: [${rawMessage}], 交易链路Id: ${traceId}"
      }
    }
  ]
}
```

### 组合路由 (Composite Router) 实战示例
针对“业务定制”与“全局公用”规则共存的翻译场景：

**1. 业务线 A 专用规则 (`translator.biz-order`)**
```json
{
  "type": "map",
  "rules": [
    { "condition": "PAY_FAIL", "value": { "code": "E001", "defaultMsg": "支付处理失败，请检查余额" } }
  ]
}
```

**2. 系统全局通用规则 (`translator.system`)**
```json
{
  "type": "expression",
  "rules": [
    { "condition": "rawCode == 'DB_TIMEOUT'", "value": { "code": "S999", "defaultMsg": "数据库繁忙" } }
  ],
  "fallbackValue": { "code": "UNKNOWN", "defaultMsg": "系统未知报错: ${rawMessage}" }
}
```

**3. 最终网关入口组合逻辑 (`translator.main`)**
```json
{
  "id": "translator.main",
  "type": "composite",
  "ext": {
    "delegates": [
      "translator.biz-order",   // 高优先级：先匹配业务私有定义
      "translator.system"      // 低优先级：后匹配系统全局兜底
    ]
  }
}
```

在代码中，你只需调用 `translator.translate(request, "translator.main", args)`，即可享受到由于“订单业务规则”与“通用报错中心”聚合带来的契约翻译便利。

### 内置的渲染管道流转

命中路由规则后，会加载 `RenderContext` 并按照内置 `RenderPolicy` 接口的优先级别进行组装加工：

1. **`FallbackRenderPolicy`（优先级：最高，保证前置兜底）**  
   若静态配置中缺失必要的 `code` 或者是 `defaultMsg`，兜底策略将优先使用 `RawResponse` 中的对应来源信息将其填满。
2. **`I18nRenderPolicy`（优先级：中级，处理多语境）**  
   该节点将探索 `ErrorDef` 中是否定义了 `i18nKey`。如果发现且本地多语言源存在该语境，将直接覆写上个管线传来的默认文案；若无，安静路过。
3. **`TemplateRenderPolicy`（优先级：默认，完成最终拼装）**  
   执行真正的变量格式化动作。将文本中类似 `${xxx}` 的占位符自动匹配透传的 `args` 以及 `rawCode` 或 `rawMessage` 占位，形成人类可读的最终报错。

---

## 扩展渲染器

如果内置管线仍未满足特定需求（如增加脱敏、字段增补），你只需要继承 `RenderPolicy` 接口即可享受 SPI 动态织入的便捷：

1. **编写自定义策略实现类**
   ```java
   public class MyDesensitizationPolicy implements RenderPolicy {
       @Override
       public int priority() {
           return NORMAL - 100; // 在模板之后执行，清理不当文字
       }

       @Override
       public boolean supports(RenderContext context) {
           return true; 
       }

       @Override
       public void render(RenderContext context) {
           String currentMsg = context.getFinalMessage();
           if (currentMsg != null && currentMsg.contains("138")) {
               context.setFinalMessage(currentMsg.replaceAll("138\\d{8}", "138****"));
           }
       }
   }
   ```

2. **追加 SPI 声明**  
   在工程根路径新建或进入文本文件：`src/main/resources/META-INF/services/com.team4u.framework.translator.render.RenderPolicy`，增加全限定类路径即可：
   ```text
   com.yourcompany.project.MyDesensitizationPolicy
   ```
   下次随着引擎启动，你的这道管线屏障即插即用生效。
