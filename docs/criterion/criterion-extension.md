# 扩展机制与 SPI

Criterion 具备高度可扩展的架构，支持自定义比较操作符、类型转换器、语法处理器与编译器。

---

## 注册自定义操作符 (addOperator)

这是最轻量、最高频的扩展方式。当需要增加一个新的比较符号（例如 IP 网段匹配、模糊包含等）时，一行代码即可完成：

```java
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.CriterionBootstrap;

import java.util.Collections;

// 方式 A：全局注册（推荐在应用启动阶段执行）
CriterionBootstrap.global()
        .addOperator("in_subnet", (actual, expected) -> {
            if (actual == null || expected == null) return false;
            return String.valueOf(actual).startsWith(String.valueOf(expected));
        })
        .lock(); // 锁定全局注册表

// 方式 B：局部 Builder 隔离构建
Criteria criteria = Criteria.builder()
        .addOperator("startsWith", (a, e) -> a.toString().startsWith(e.toString()))
        .build();

// 直接在 DSL 规则中使用自定义算子
boolean isInternal = Criteria.global().matches(
        "clientIp in_subnet '192.168.1.'", 
        Collections.singletonMap("clientIp", "192.168.1.100")
); // true
```

---

## 注册自定义类型转换器 (ValueConverter)

当需要对特定业务实体（如 `Money`, `DataSize`, `GeoPoint`）执行前置转换并比较时，实现 `ValueConverter` 接口：

```java
import com.team4u.framework.criterion.CriterionBootstrap;
import com.team4u.framework.criterion.model.convert.ValueConverter;
import java.math.BigDecimal;

public class MoneyValueConverter implements ValueConverter {

    @Override
    public String key() {
        return "money"; // 对应语法中的 :money 前缀/后缀
    }

    @Override
    public Comparable<?> apply(Object obj) {
        if (obj == null) return null;
        return new BigDecimal(String.valueOf(obj));
    }
}
```

### 注册转换器：
- **通过全局引导注册**：
  ```java
  CriterionBootstrap.global().addConverter(new MoneyValueConverter());
  ```
- **通过 Builder 注册**：
  ```java
  Criteria criteria = Criteria.builder().addValueConverter(new MoneyValueConverter()).build();
  ```
- **Java SPI 自动加载**：
  在 `META-INF/services/com.team4u.framework.criterion.model.convert.ValueConverter` 中声明类全路径。

### 使用转换器：
```java
boolean match = Criteria.global().matches("price:money >= '99.99'", product);
```

---

## 深度 SPI 编译器与语法定制

若需要定制全新的 DSL 语法结构（例如 `it has_permission 'USER_READ'`）：

### 步骤 1：定义 AST 节点模型
```java
import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.CriterionVisitor;
import lombok.Getter;

@Getter
public class PermissionCriterion implements Criterion {
    private final String permission;
    private String expression;

    public PermissionCriterion(String permission) {
        this.permission = permission;
    }

    @Override
    public <R> R accept(CriterionVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
```

### 步骤 2：实现 SyntaxHandler 语法识别
```java
import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.parser.CriterionParser;
import com.team4u.framework.criterion.parser.SyntaxHandler;

public class PermissionSyntaxHandler implements SyntaxHandler {

    @Override
    public Criterion tryParse(String subject, CriterionParser.Context context) {
        if (!context.match("has_permission")) {
            return null;
        }
        String permission = context.consumeValue();
        return context.wrapProperty(subject, new PermissionCriterion(permission));
    }
}
```

### 步骤 3：实现 CriterionCompiler 编译器
```java
import com.team4u.framework.criterion.MatchPredicate;
import com.team4u.framework.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.framework.criterion.model.CriterionVisitor;

public class PermissionCriterionCompiler extends AbstractCriterionCompiler<PermissionCriterion> {

    @Override
    public MatchPredicate compile(PermissionCriterion criterion, CriterionVisitor<MatchPredicate> visitor) {
        String required = criterion.getPermission();
        return safe(context -> {
            Object actual = context.getActual();
            // 执行权限检查逻辑
            return SecurityContext.hasPermission(actual, required);
        });
    }

    @Override
    public Class<? extends Criterion> key() {
        return PermissionCriterion.class;
    }
}
```

### 步骤 4：组装或 SPI 注册
```java
Criteria customCriteria = Criteria.builder()
        .addSyntaxHandler(new PermissionSyntaxHandler())
        .addCompiler(new PermissionCriterionCompiler())
        .build();
```

---

## 沙箱环境与完全隔离 (`clear`)

在需要严格安全隔离的多租户或用户自定义脚本执行沙箱中，可通过 `Criteria.builder().clear()` 清除所有预置策略，仅开放受控的安全算子：

```java
Criteria sandboxCriteria = Criteria.builder()
        .clear() // 清空所有默认编译器与转换器
        .addOperator("==", Object::equals)
        .build();
```
