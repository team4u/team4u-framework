# 快速开始

本文介绍如何在项目中引入并使用 `team4u-criterion` 表达式引擎。

---

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-criterion</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

> [!NOTE]
> `Criteria` 实例是不可变且线程安全的，建议全局单例复用；`MatchContext` 为每次判定的请求级上下文对象，并发场景下请为每个请求创建独立实例。

---

## 基础比较与规则判定

```java
import com.team4u.framework.criterion.Criteria;

public class CriterionQuickStart {

    public static void main(String[] args) {
        Criteria criteria = Criteria.global();

        // 1. 简单数值判定（it 代表当前入参对象）
        boolean adult = criteria.matches("it >= 18", 20); // true

        // 2. 语法糖常量隐式相等
        boolean isVip = criteria.matches("'VIP'", "VIP"); // true

        // 3. POJO 属性访问与组合逻辑
        User user = new User("Alice", 25, "ADMIN");
        boolean match = criteria.matches("age between [18, 30] && role == 'ADMIN'", user); // true

        System.out.println("用户是否符合资格: " + match);
    }
}
```

---

## 使用 MatchContext 传递动态变量

当规则需要动态比较运行时参数（如外部传入的阈值）时，使用 `$` 前缀定义动态变量：

```java
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;

User user = new User("Bob", 28, "USER");

// 创建匹配上下文并注入动态变量
MatchContext context = MatchContext.of(user)
        .setAttribute("minAge", 18)
        .setAttribute("maxAge", 30)
        .setAttribute("allowedRoles", Arrays.asList("VIP", "USER"));

// 表达式中的 $minAge 和 $allowedRoles 自动从 context attributes 中提取
boolean isValid = Criteria.global().matches(
        "age between [$minAge, $maxAge] && role in $allowedRoles",
        context
);

System.out.println("匹配结果: " + isValid); // true
```

---

## 可视化 Trace 链路追踪

当复杂规则未命中时，使用 `trace` 接口能够快速还原规则执行树与每一步的短路状态：

```java
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.criterion.trace.TraceNode;

User user = new User("Charlie", 16, "USER");
MatchContext context = MatchContext.of(user);

// 执行 Trace
TraceNode traceNode = Criteria.global().trace("age >= 18 && role == 'ADMIN'", context);

System.out.println("判定结果: " + traceNode.isMatched()); // false
System.out.println("执行轨迹: " + traceNode.render()); 
// 输出: (age >= 18 {16}[N] AND role == 'ADMIN' {"USER"}[N])[N]
```

---

## 表达式静态分析与预热

```java
// 1. 提取表达式中定义的所有变量名
Set<String> variables = Criteria.global().getVariables("age >= $minAge && role == $targetRole");
// 输出: [age, minAge, role, targetRole]

// 2. 表达式预热（在应用启动或规则加载时提前编译，消除首次执行的 JIT 延迟）
Criteria.global().compileExpression("age between [18, 60] && status == 'ACTIVE'");
```

---

## 下一步

- 查看所有运算符、语法糖与类型转换器：[DSL 语法指南](criterion-syntax.md)
- 了解 AST 编译、低分配优化与延迟属性加载：[编译与低分配优化](criterion-compiler.md)
- 深入掌握白盒排障工具：[执行链路追踪 Trace](criterion-trace.md)
- 自定义算子与 Spring 整合：[扩展机制与 SPI](criterion-extension.md)
