# 快速开始

本文将指导你如何在 3 分钟内完成 `team4u-router` 的引入与快速接入。

---

## 引入依赖

在项目的 `pom.xml` 中引入 `team4u-router` 模块：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-router</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

> [!NOTE]
> `team4u-router` 内部已传递引入 `team4u-criterion`、`team4u-config-core`、`team4u-base` 与 `team4u-serializer-json`。声明式代理、`@Routed` 与 Bean 定位需要额外引入 `team4u-router-proxy`；JSON 配置解析还需应用显式提供 `team4u-serializer-jackson` 或自定义 `JsonSerializerPolicy`。

---

## 准备路由规则配置

在配置中心（如 Nacos / Apollo / 本地配置文件）中定义路由策略（以 JSON 结构下发）。

默认情况下，配置 Key 遵循 `router.{routerId}` 命名约定（例如 `router.order-router`）：

```json
{
  "id": "order-router",
  "type": "expression",
  "rules": [
    {
      "condition": "region == 'CN' && amount >= 1000",
      "value": "china-vip-handler"
    },
    {
      "condition": "region == 'CN'",
      "value": "china-standard-handler"
    },
    {
      "condition": "region == 'US'",
      "value": "us-handler"
    }
  ],
  "fallbackValue": "global-default-handler"
}
```

---

## 获取 RoutingManager 实例

`RoutingManager` 是执行路由决策的核心门面，支持全局单例复用或使用 Builder 隔离构建：

### 方式 A：使用标准全局单例（推荐）

全局单例会自动扫描 SPI 扩展工厂，并默认绑定到全局配置中心 (`ConfigManager.global()`)：

```java
import com.team4u.framework.router.RoutingManager;

RoutingManager manager = RoutingManager.global();
```

### 方式 B：使用 Builder 深度定制

适用于单元测试、多租户独立隔离环境或特定配置前缀：

```java
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.router.RoutingManager;

RoutingManager customManager = RoutingManager.builder()
        .configPrefix("biz.router.") // 自定义配置前缀（默认为 router.）
        .configManager(ConfigManager.global()) // 绑定指定的配置管理器
        .build();
```

---

## 执行路由判定

### 基础调用

```java
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.api.model.RouteResult;

import java.util.HashMap;
import java.util.Map;

public class QuickStartDemo {

    public static void main(String[] args) {
        RoutingManager manager = RoutingManager.global();

        // 1. 准备请求上下文（支持 Map、POJO 对象或基本类型）
        Map<String, Object> context = new HashMap<>();
        context.put("region", "CN");
        context.put("amount", 2000);

        // 2. 执行路由（传入 routerId，内部自动定位 router.order-router 配置）
        RouteResult<String> result = manager.route("order-router", context, String.class);

        // 3. 消费路由结果
        if (result.isMatch()) {
            System.out.println("命中结果: " + result.getValue()); 
            // 输出: china-vip-handler
            
            System.out.println("命中的条件: " + result.getMatchedCondition()); 
            // 输出: region == 'CN' && amount >= 1000
            
            System.out.println("命中语义: " + result.getOutcome()); 
            // 输出: RULE_MATCH
        } else {
            System.out.println("未命中任何规则");
        }
    }
}
```

### 泛型与复杂对象类型转换 (`TypeReference`)

当路由返回的是复杂对象（如 POJO 或泛型集合 `List<T>`）时，可传入 `TypeReference` 确保类型安全转换：

```java
import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.api.model.RouteResult;

import java.util.List;

// 自动将配置中的 JSON 数组转换为 List<String>
RouteResult<List<String>> listResult = RoutingManager.global().route(
        "tag-router",
        userContext,
        new TypeReference<List<String>>() {}
);

if (listResult.isMatch()) {
    List<String> tags = listResult.getValue();
}
```

---

## 编程式路由（无需配置文件）

在单元测试或动态构建策略场景中，可以直接使用 `RoutePolicyBuilder` 构建规则，并通过 `routeByPolicy` 执行：

```java
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.api.builder.RoutePolicyBuilder;
import com.team4u.framework.router.api.model.RoutePolicy;
import com.team4u.framework.router.api.model.RouteResult;

import java.util.Collections;
import java.util.Map;

// 1. 流式构建规则策略
RoutePolicy policy = RoutePolicyBuilder.<String>expression()
        .id("test-router")
        .rule("age >= 18", "adult-service")
        .rule("age < 18", "minor-service")
        .fallback("guest-service")
        .build();

// 2. 直接根据策略对象执行路由
Map<String, Object> user = Collections.singletonMap("age", 20);
RouteResult<String> result = RoutingManager.global().routeByPolicy(policy, user, String.class);

System.out.println(result.getValue()); // adult-service
```

---

## 临时字符串配置路由 (`routeByConfig`)

方便直接透传原始 JSON 配置字符串进行单元测试：

```java
String rawJson = "{\"type\":\"map\",\"rules\":[{\"condition\":\"VIP\",\"value\":\"vipService\"}],\"fallbackValue\":\"commonService\"}";

RouteResult<String> result = RoutingManager.global().routeByConfig(rawJson, "VIP", String.class);
System.out.println(result.getValue()); // vipService
```

---

## 下一步

- 深入了解四种核心路由器特性：[路由器类型](router-types.md)
- 使用注解实现接口透明动态路由：[声明式路由](router-declarative.md)
- 探索责任链拦截器与上下文注入：[路由拦截器](router-interceptor.md)
- 白盒排障与诊断轨迹：[路由诊断与 Trace](router-trace.md)
- SPI 扩展与高级生命周期锁：[SPI 扩展与高级配置](router-spi.md)
