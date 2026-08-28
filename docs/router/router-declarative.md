# 声明式路由与动态代理

声明式路由属于独立适配模块 `team4u-router-proxy`。`@Routed`、`@RouteContext`、`RoutedProxyFactory`、`RoutedBeanLocator`、`BeanResolver` 与 `RoutedMethodInterceptor` 的 FQCN 保持不变，但需要在 `team4u-router` 之外显式引入该适配模块：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-router-proxy</artifactId>
</dependency>
```

只使用 `RoutingManager`、路由策略、Trace 或拦截器时，无需引入该模块；`team4u-translator` 也只依赖 router core。
常规 API 面向接口并由 JDK 动态代理实现；代理具体类时会回退到可选的 ByteBuddy 依赖。

在企业级架构中，将底层路由决策逻辑与上层业务代码彻底解耦是保持架构整洁的关键。`team4u-router-proxy` 提供了基于注解的声明式路由机制：业务方只需声明业务接口并标注注解，框架通过动态代理自动拦截方法调用，完成参数提取、动态 ID 渲染、路由判定与目标 Bean 定位分派。

---

## 核心注解

### `@Routed`
可标注在业务接口、类或具体方法上（方法级注解优先于类级注解）：

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Routed {
    /**
     * 路由策略的唯一标识 (对应配置中心的 router.{routerId})
     * 支持 ${property} 动态占位符模板
     */
    String routerId();
}
```

### `@RouteContext`
标注在接口方法的参数上，指示该参数对象作为路由计算的上下文：

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RouteContext {
}
```

---

## 动态 Router ID 替换机制

`@Routed.routerId` 内部通过 `TextTemplate` 模板引擎与正则 `\$\{([^}]+)\}` 进行变量解析：

### 规则与约束机制

| 场景 | 上下文类型 | 模板示例 | 行为与约束 |
| :--- | :--- | :--- | :--- |
| **常量模式** | 任意 | `routerId = "payment-router"` | 直接定位配置 `router.payment-router`，不进行变量替换 |
| **POJO 多占位符** | POJO 对象 / Map | `routerId = "biz.${region}.${tenantId}.router"` | 框架通过 `BeanUtil.getProperty(context, prop)` 分别提取 `region` 与 `tenantId` 属性并拼接 |
| **简单类型单占位符** | 基本类型 / String / Long 等 | `routerId = "router.user_${userId}"` | 简单类型直接将其值整体替换该占位符 |
| **异常分支校验** | 基本类型 / String / Long 等 | `routerId = "biz.${tenant}.${env}"` | **严格校验**：当上下文为简单类型时，若模板中包含多于 1 个占位符，框架将抛出 `RouteConfigException.validationError` |

### 上下文参数提取规则
1. **显式标注**：方法中被 `@RouteContext` 标注的参数作为上下文。
2. **多注解校验**：单个方法内**严禁标注多个** `@RouteContext` 参数，否则在解析方法元数据时抛出 `RouteConfigException.validationError`。
3. **默认兜底**：若方法内未标注任何 `@RouteContext`，框架默认将**第一个入参 (`args[0]`)** 作为路由上下文。

---

## 完整接入示例

### 定义业务接口与入参模型

```java
public interface PaymentService {

    @Routed(routerId = "payment.${region}.router")
    String pay(@RouteContext PaymentOrder order);
}

@Data
public class PaymentOrder {
    private String region;      // 如 "CN", "US"
    private double amount;      // 订单金额
    private String channel;     // 如 "ALIPAY", "WECHAT"
}
```

### 编写多套业务实现 Bean

在 Spring 或 `team4u-bean` 容器中注册不同的实现：

```java
@Component("chinaAlipayPaymentService")
public class ChinaAlipayPaymentService implements PaymentService {
    @Override
    public String pay(PaymentOrder order) {
        return "CN Alipay Success: " + order.getAmount();
    }
}

@Component("usCardPaymentService")
public class UsCardPaymentService implements PaymentService {
    @Override
    public String pay(PaymentOrder order) {
        return "US CreditCard Success: " + order.getAmount();
    }
}

@Component("defaultPaymentService")
public class DefaultPaymentService implements PaymentService {
    @Override
    public String pay(PaymentOrder order) {
        return "Default Process: " + order.getAmount();
    }
}
```

### 配置中心下发路由规则

配置键 `router.payment.CN.router`：
```json
{
  "id": "payment.CN.router",
  "type": "expression",
  "rules": [
    {
      "condition": "channel == 'ALIPAY'",
      "value": "chinaAlipayPaymentService"
    }
  ],
  "fallbackValue": "defaultPaymentService"
}
```

### 创建并调用动态代理

通过 `RoutedProxyFactory` 一键生成代理对象：

```java
import com.team4u.framework.router.proxy.RoutedProxyFactory;

// 方式 A：使用全局默认 RoutingManager 创建代理
PaymentService paymentService = RoutedProxyFactory.createProxy(PaymentService.class);

// 方式 B：使用自定义 RoutingManager 创建（适用于多租户隔离）
PaymentService customService = RoutedProxyFactory.createProxy(PaymentService.class, myRoutingManager);

// 方式 C：使用自定义 BeanResolver 创建
PaymentService resolverService = RoutedProxyFactory.createProxy(PaymentService.class, myRoutingManager, customBeanResolver);

// 执行调用：内部自动提取 order，根据 routerId 渲染为 payment.CN.router，路由定位到 "chinaAlipayPaymentService" 并执行
PaymentOrder order = new PaymentOrder();
order.setRegion("CN");
order.setChannel("ALIPAY");
order.setAmount(100.0);

String result = paymentService.pay(order);
System.out.println(result); // CN Alipay Success: 100.0
```

> [!TIP]
> **异常安全解包**：当目标 Bean 执行抛出业务异常时，`RoutedMethodInterceptor` 会自动解开反射调用的 `InvocationTargetException` 包装，将真实的业务异常抛给上层调用方。

---

## 手动定位 Bean (RoutedBeanLocator)

如果你不想使用动态代理，也可以直接使用 `RoutedBeanLocator` 根据规则手动定位目标 Bean 实例：

```java
import com.team4u.framework.router.proxy.RoutedBeanLocator;

// 1. 全局定位
PaymentService service = RoutedBeanLocator.locate(
        "payment.CN.router",
        order,
        PaymentService.class
);

// 2. 自定义 Manager 与 BeanResolver 定位
PaymentService customLocated = RoutedBeanLocator.locate(
        myRoutingManager,
        customBeanResolver,
        "payment.CN.router",
        order,
        PaymentService.class
);

service.pay(order);
```

### `RoutedBeanLocator` 异常处理体系

`RoutedBeanLocator` 内部包含完整的契约安全校验：

1. **未命中规则且无兜底**：抛出 `RouteNotFoundException.ruleNotMatched(routerId)`（错误码 `RULE_NOT_MATCHED`）。
2. **容器中未找到对应 Bean**：抛出 `RouteNotFoundException.beanNotFound(routerId, targetBeanName)`（错误码 `BEAN_NOT_FOUND`）。
3. **Bean 类型不匹配**：若从容器中获取的 Bean 并非 `expectedType` 的实例，抛出 `RouteException.typeMismatch(...)`（错误码 `TYPE_MISMATCH`）。

---

## 自定义 Bean 解析器 (BeanResolver)

默认情况下，框架使用 `BeanManager.getInstance().getBean(beanName)` 定位对象。在非默认容器或复杂 Spring 多上下文环境下，可实现 `BeanResolver` 接口：

```java
import com.team4u.framework.router.proxy.BeanResolver;
import org.springframework.context.ApplicationContext;

public class SpringBeanResolver implements BeanResolver {

    private final ApplicationContext applicationContext;

    public SpringBeanResolver(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object getBean(String beanName) {
        return applicationContext.getBean(beanName);
    }
}
```

---

## 路由 ID 与配置前缀设计哲学

为了降低业务代码对具体物理配置路径的依赖，`team4u-router` 采用 **“关注逻辑 ID，隐藏物理前缀”** 的设计：

- **自动前缀补全**：`RoutingManager` 默认前缀为 `router.`。调用 `route("order-router")` 时自动查找 `router.order-router`。
- **自动去重防御**：若传入的 ID 已经包含了前缀（如 `route("router.order-router")`），框架会自动识别并避免重复拼接。
- **全局前缀可配**：在应用启动阶段通过 `RouterBootstrap.global().configPrefix("my.biz.router.")` 一处修改，即可全局生效，无需变动业务注解。
