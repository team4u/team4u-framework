# 快速开始

本文介绍如何在项目中快速接入并使用 `team4u-translator`。

---

## 引入依赖

在项目的 `pom.xml` 中引入 `team4u-translator` 模块：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-translator</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

> [!NOTE]
> `team4u-translator` 内部已传递引入 `team4u-router`、`team4u-criterion` 与 `team4u-policy`，无需重复引入基础依赖。

---

## 准备路由规则配置

在配置中心定义错误码映射规则（以 JSON 结构下发，由 `team4u-router` 自动监听解析）：

配置键：`router.error-router`
```json
{
  "id": "error-router",
  "type": "expression",
  "rules": [
    {
      "condition": "domain == 'ORDER_CENTER' && code == 'STOCK_ZERO'",
      "value": {
        "code": "PRODUCT_SOLD_OUT",
        "defaultMsg": "抱歉，您在【${action}】时商品已售罄，原始信息：${rawMessage}"
      }
    },
    {
      "condition": "code == 'DB_TIMEOUT'",
      "value": {
        "code": "SYSTEM_BUSY",
        "defaultMsg": "系统繁忙[${rawCode}]，请稍后重试"
      }
    }
  ],
  "fallbackValue": {
    "code": "SYSTEM_ERROR",
    "defaultMsg": "系统开小差了，请稍后再试"
  }
}
```

---

## 执行翻译

```java
import com.team4u.framework.translator.api.ResponseTranslator;
import com.team4u.framework.translator.engine.DefaultResponseTranslator;
import com.team4u.framework.translator.model.RawResponse;
import com.team4u.framework.translator.model.TranslatedResponse;

import java.util.HashMap;
import java.util.Map;

public class TranslatorQuickStart {

    public static void main(String[] args) {
        // 1. 初始化翻译器门面（使用全局默认路由管理器）
        ResponseTranslator translator = new DefaultResponseTranslator();

        // 2. 构造上游原始响应（来源域、原始错误码、原始描述信息）
        RawResponse raw = RawResponse.of("ORDER_CENTER", "STOCK_ZERO", "库存不足 0 件");

        // 3. 组装业务动态参数（支持 traceId 与业务占位符变量）
        Map<String, Object> argsMap = new HashMap<>();
        argsMap.put("traceId", "tid-20260825-001");
        argsMap.put("action", "提交订单");

        // 4. 执行翻译（传入 routerId，内部自动查找 router.error-router 路由策略）
        TranslatedResponse response = translator.translate(raw, "error-router", argsMap);

        // 5. 消费翻译结果
        System.out.println("对外标准化错误码: " + response.getCode());
        // 输出: PRODUCT_SOLD_OUT
        
        System.out.println("对外标准化文案: " + response.getMessage());
        // 输出: 抱歉，您在【提交订单】时商品已售罄，原始信息：库存不足 0 件
        
        System.out.println("链路追踪标识 TraceId: " + response.getTraceId());
        // 输出: tid-20260825-001
    }
}
```

---

## 构造器定制与包扫描

如果你的项目需要自定义 `RoutingManager` 或扩展自定义渲染策略包路径：

```java
// 方式 A：指定独立的 RoutingManager 实例
ResponseTranslator customTranslator = new DefaultResponseTranslator(myRoutingManager);

// 方式 B：指定自定义渲染策略扫描包
ResponseTranslator scanTranslator = new DefaultResponseTranslator(
        "com.mycompany.service.render"
);

// 方式 C：同时指定 RoutingManager 与包扫描
ResponseTranslator fullTranslator = new DefaultResponseTranslator(
        myRoutingManager,
        "com.mycompany.service.render"
);
```

---

## 下一步

- 深入了解核心数据模型与执行流转：[核心模型与执行流程](translator-model.md)
- 探索模板变量插值与自定义 RenderPolicy：[模板渲染与策略扩展](translator-render.md)
- 结合 CompositeRouter 打造多业务线错误码系统：[结合 Router 组合路由](translator-routing.md)
- 查看生产环境全量实战范例：[实战案例](translator-sample.md)
