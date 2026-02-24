# Criterion 表达式模块

[![JDK 8+](https://img.shields.io/badge/JDK-8+-green.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

## 目录
- [简介](#简介)
- [快速入门](#快速入门)
- [典型场景与核心优势](#典型场景与核心优势)
- [语法指南](#语法指南)
- [高级进阶](#高级进阶)
- [自定义扩展 (SPI)](#自定义扩展-spi)
- [架构与原理](#架构与原理)

## 简介

`team4u-criterion` 是一个轻量级、高性能、可扩展的 Java 表达式引擎。它不是解决“A + B 等于几”的数学计算问题，而是为了解决 “业务规则如何高效表达、如何灵活路由、如何快速排障” 的领域问题。

在营销系统人群圈选、网关灰度路由、风控规则拦截等场景下，它在易用性、安全性和生产稳定性（可视化追踪）上，比 SpEL 或 Aviator 等通用表达式引擎更具优势。

### 核心特性
* DSL 自然语义：无限接近 SQL 或自然语言的表达式（如 `age > 18`，`name is not empty`）。
* 纳秒级极速执行：JIT 风格的编译模式，将表达式树编译为直出的闭包函数（`MatchPredicate`）。
* 0 GC 与智能宽容比较：内置智能宽容比较，底层屏蔽数值类型差异；针对整数/浮点比较提供全程 0 GC 核心路径。
* Trace 可视化追踪：内置 `TraceRecorder`，打印完整逻辑执行树，复杂规则排障一目了然。
* 延迟加载 (Lazy Resolve)：支持按需加载外部属性（如 RPC/DB 查询），配合短路逻辑将性能消耗降到最低。
* 安全容错不抛异常：引擎默认安全容错，遇异常不报错不阻断；也可灵活开启严格模式校验。
* 灰度与扩展性：内置随机与哈希分流算子用于灰度发布；一行代码即可扩展自定义操作符。

---

## 快速入门

> ⚠️ 强烈警告：`Criteria` 是线程安全且建议单例复用的；但 `MatchContext` 是非线程安全的，每个并发请求必须创建独立的 `MatchContext` 实例！

### 引入依赖

```xml
<dependency>
    <groupId>org.team4u</groupId>
    <artifactId>team4u-base</artifactId>
    <version>2.30.0-SNAPSHOT</version>
</dependency>
```

### 获取 Criteria 实例与基础配置

`Criteria` 实例是不可变的，支持单例复用：

```java
// 标准实例（推荐）：适用于大多数场景，无需任何配置，直接复用全局单例
Criteria criteria = Criteria.standard();
```

完美适配 Spring:
```java
@Configuration
public class CriteriaConfig {
    @Bean
    public Criteria defaultCriteria() {
        return Criteria.standard();
    }
}
```

### 基础用法Demo

```java
import org.team4u.base.criterion.Criteria;
import org.team4u.base.criterion.spi.MatchContext;

public class Demo {
    static void main(String[] args) {
        Criteria criteria = Criteria.standard();

        // 简单比较与语法糖隐式相等
        boolean isAdult = criteria.matches("it > 18", 20); // true
        boolean isEighteen = criteria.matches("18", 18); // true

        // 针对对象的属性灵活访问
        User user = new User("Alice", 25, "admin");
        // 表达式：role 等于 'admin' 且 age 在 [20, 30] 之间
        boolean match = criteria.matches("role == admin && age between [20, 30]", user); // true

        // 转换器类型转换 (如日期比较)
        boolean isNew = criteria.matches("createTime:date > '2023-01-01'", user); // true

        // 使用 MatchContext 传递动态变量/额外属性
        MatchContext context = MatchContext.of(user)
            .setAttribute("minAge", 18)
            .setAttribute("maxAge", 30);
        // 动态变量必须以 $ 开头
        boolean ctxMatch = criteria.matches("age between [$minAge, $maxAge]", context); // true
        
        // 变量名维度预先提取
        Set<String> vars = criteria.getVariables("age > $minAge && role == admin"); // [age, minAge, role]
    }
}
```

---

## 典型场景与核心优势

为了直观地展示 `Criterion` 的核心竞争力，以下是 4 个典型的应用场景示例。这些场景体现了它在业务表达力、执行追踪、流量灰度以及高扩展性上相较于通用表达式引擎（如 SpEL, Aviator）的独特优势。

### 场景 1：极具业务语义的 DSL（告别繁琐的函数嵌套）

普通的表达式引擎在处理区间、空值、集合关系时，通常需要冗长的函数调用。`Criterion` 提供了无限接近自然语言的 DSL，让业务规则一目了然。

```java
public void example1_BusinessDSL() {
    Criteria criteria = Criteria.standard();

    // 准备用户上下文（可以是 POJO 或 Map）
    MatchContext context = MatchContext.of(new HashMap<String, Object>() {{
        put("age", 25);
        put("role", "VIP");
        put("tags", Arrays.asList("game", "music"));
        put("status", "active");
    }});

    // 表达式极其易读，对产品和运营人员非常友好
    String expression = "age between [18, 35] " +
                        "&& role in [VIP, SVIP] " +
                        "&& tags contains game " +
                        "&& status is not empty";

    boolean isMatch = criteria.matches(expression, context);
    System.out.println("匹配结果: " + isMatch); // 输出: true
}
```

### 场景 2：可视化执行追踪 (TraceRecorder) — 排障神器

在线上环境中排查“规则为何未命中”时，黑盒引擎往往难以定位。`Criterion` 内置了 `TraceRecorder`，能够完整还原逻辑执行树，包括实际运行值和每一步的短路结果。

```java
public void example2_ExecutionTrace() {
    Criteria criteria = Criteria.standard();

    MatchContext context = MatchContext.of(new HashMap<String, Object>() {{
        put("age", 16); // 年龄不满足条件
        put("role", "user");
        put("score", 95);
    }});

    // 规则：成年人，且（是管理员 或 积分大于 90）
    String expression = "age >= 18 && (role == admin || score > 90)";

    // 使用 trace 方法获取执行轨迹
    TraceNode rootNode = criteria.trace(expression, context);

    // 打印可视化追踪树
    System.out.println("匹配结果: " + rootNode.isMatched());
    System.out.println("执行轨迹: " + rootNode.render());
}
```

控制台输出：
```text
匹配结果: false
执行轨迹: (age >= 18 {16}[N] AND (role == admin {"user"}[N] OR score > 90 {95}[Y])[Y])[N]
```
> 提示：一眼即可看出 `age >= 18` 实际值为 `16` 导致了 `AND` 逻辑短路。这种“白盒”排障能力在复杂业务场景下极具价值。

### 场景 3：开箱即用的“灰度发布与 A/B 测试”

不同于通用的逻辑运算引擎，`Criterion` 内置了一致性 `hash` 和随机 `prob` 机制。配合 `:version` 转换器，它可以直接作为灰度路由的核心组件。

```java
public void example3_GrayReleaseAndABTest() {
    Criteria criteria = Criteria.standard();

    MatchContext context = MatchContext.of(new HashMap<String, Object>() {{
        put("userId", "10086");
        put("appVersion", "2.1.0");
    }});
    // 注入盐值，确保不同实验的分流结果正交（打散更均匀）
    context.setAttribute("salt", "NewHomePage_Experiment");

    // 复杂的灰度规则：
    // 1. App 版本必须大于等于 2.0.0
    // 2. 根据 userId 进行 Hash 计算，圈选 30% 的用户（确定性分流，同一用户结果不变）
    // 3. 全局 10% 的纯随机概率命中（辅助逻辑）
    String expression = "appVersion:version >= '2.0.0' " +
                        "&& userId hash 0.3 " +
                        "&& it prob 0.1";

    boolean isMatch = criteria.matches(expression, context);
    System.out.println("是否命中灰度新版: " + isMatch); 
}
```

### 场景 4：高扩展性 —— 一行代码扩展自定义操作符

面对特殊的业务垂直场景（例如特定的权限校验、复杂的业务状态判断、特有的 IP 网段校验等），传统引擎往往需要使用繁琐的函数调用或改写底层逻辑。`Criterion` 提供极致的开箱扩展能力，只需简单注册自定义操作符，它就能无缝融入标准的 DSL 语法，并与内置逻辑符和动态变量组合协同工作。

```java
public void example4_Extensibility() {
    // 业务场景：标准比较算子无法满足针对 IP 地址的网段前缀匹配功能，我们需要扩展带有业务属性的 `in_subnet` 算子
    Criteria criteria = Criteria.builder()
        // 一行核心代码即可完成自定义操作符的注册
        .addOperator("in_subnet", (ip, prefix) -> String.valueOf(ip).startsWith(String.valueOf(prefix)))
        .build();

    MatchContext context = MatchContext.of("192.168.10.88")
        .setAttribute("internalPrefix", "10.0.0.");

    // 使用 `it` 关键字代表当前对象，支持与全局逻辑符 (&&, ||) 及上下文变量无缝混用
    String expression = "it in_subnet '192.168.10.' || it in_subnet $internalPrefix";
    
    boolean isMatch = criteria.matches(expression, context);
    System.out.println("是否为安全授权网段: " + isMatch); // 输出: true
}
```



## 语法指南

引擎支持丰富的表达式语法。核心规则如下：

1. Subject（主语/属性）：无 `$` 前缀（如 `age`），代表从 `MatchContext.getActual()` 对象中提取的属性。
2. Variable（上下文变量）：强制 `$` 前缀（如 `$minAge`），代表从 `MatchContext.getAttributes()` 中提取的参数。
3. Literal（字面量/常量）：
    * 数字/布尔/null：直接书写。
    * 带引号字符串：使用单引号 `'` 包裹（如 `'admin'`）。引号内字符均为字面量，包括 `$`（如 `'Price is $100'`）。
    * 无引号字符串：直接书写普通单词（如 `ACTIVE`），引擎会自动识别。

### 基础比较

| 操作符  | 说明   | 示例                    |
|------|------|-----------------------|
| `>`  | 大于   | `age > 18`            |
| `>=` | 大于等于 | `score >= 60`         |
| `<`  | 小于   | `price < 100`         |
| `<=` | 小于等于 | `count <= 5`          |
| `==` | 等于   | `role == 'admin'`     |
| `!=` | 不等于  | `status != 'deleted'` |

> 隐式相等：若表达式仅为一个常量值（如 `18` 或 `'admin'`），引擎会将其解析为 `it == {value}`。
>
> ⚠️ 注意：引擎仅支持 `Subject 操作符 Value` 的格式（如 `role == 'admin'`），不支持反向格式（即 `Value 操作符 Subject`，如 `'admin' == role`）。

### 智能宽容比较
引擎底层彻底屏蔽了数值类型的差异。无论实际传入的是 `Integer`, `Long`, `Double` 还是字符串 `"1"`，只要数值逻辑相等（如 `1 == 1.0`），引擎都能精准匹配，彻底告别反序列化等场景带来的繁琐类型转换异常。

### 逻辑组合

支持使用 `&&` (与) 和 `||` (或) 组合多个条件，通过 `()` 调整优先级。

* 与：`age > 18 && gender == 'male'`
* 或：`type == 'A' || type == 'B'`
* 组合：`(age > 60 || type == 'VIP') && active == true`

### 空值检查 (Is / Is Not)

* Null 检查：`name is null` 或 `name is not null`
* 空值检查 (Null、空字符串或空集合)：`tags is empty`
* 非空检查：`name is not empty`

### 集合操作 (In / Contains)

* In (成员包含)：判断值是否在指定集合中。
    * `status in ['active', 'pending']` (支持常量折叠与 $O(\log N)$ 优化)
  * `status in [ACTIVE, PENDING]` (无引号字符串同样支持 $O(\log N)$ 优化)
    * `id not in [1, 2, 3]` (排除匹配)
  * `id in [1, 2, $specialId]` (支持字面量与变量混合)
  * `id in $group` (引用上下文中的集合变量 `$group`)

* Contains (包含元素)：判断集合是否包含元素，或字符串是否包含子串。
    * `roles contains 'admin'` (roles 为列表或集合)
    * `description contains 'error'` (字符串模糊匹配)
    * `ids contains 100` (数值匹配)

* ContainsAny (交集检查)：判断集合是否存在交集。
    * `tags containsAny ['VIP', 'KOL']` (静态列表，有交集返回 true)
    * `roles containsAny $requiredRoles` (引用上下文变量)

* ContainsAll (全集包含)：判断实际集合是否完全包含预期集合的所有元素。
    * `userTags containsAll ['VIP', 'KOL']` (静态列表，完全包含返回 true)
    * `userTags containsAll $requiredTags` (引用上下文变量)
    * 空期望集始终返回 true（空集是任何集合的子集）

### 区间判断 (Between)

支持标准数学区间表示法。`[` 代表包含，`(` 代表不包含。

* 全闭区间：`age between [18, 30]` (18 ≤ age ≤ 30)
* 左闭右开：`score between [60, 90)` (60 ≤ score < 90)
* 动态区间：`price between [$minPrice, $maxPrice]` (引用变量)

### 文本匹配 (Regex / Like)

* 正则匹配 (`=~`)：`email =~ '.*@example\\.com$'`
* 通配符匹配 (`like`)：支持 `*` (多个字符) 和 `?` (单个字符)。`name like 'J*'`

### 时间比较 (Date)

通过 `subject:date` 将属性转换为日期进行比较。支持多种日期格式及 `now` 关键字。

| 说明       | 示例                                         |
|----------|--------------------------------------------|
| 基础日期比较   | `createTime:date > '2023-01-01'`          |
| 包含时间的比较  | `it:date >= '2023-01-01 12:00:00'`        |
| 相对当前时间比较 | `expireTime:date < 'now'`                 |
| 时间区间判断   | `t:date between ['09:00:00', '18:00:00')` |

### 版本比较 (Version)

通过 `subject:version` 将属性转换为语义化版本号（Semantic Versioning）进行比较：

| 说明     | 示例                                       |
|--------|------------------------------------------|
| 版本范围比较 | `appVersion:version >= '2.1.5'`         |
| 版本区间判断 | `it:version between ['1.0.0', '2.0.0')` |
| 精确版本匹配 | `appVersion:version == '1.0.1'`         |

### 显式类型转换 (Converter)

除了日期和版本，还支持其他内置转换器：

| 转换器 | 说明 | 示例 |
|------|------|------|
| `date` | 日期转换器 | `createTime:date > '2023-01-01'` |
| `version` | 版本号转换器 | `appVersion:version >= '2.1.5'` |
| `number` | 数值转换器 | `price:number > 100` |
| `string` | 字符串转换器 | `name:string == 'test'` |
| `size` | 大小/长度转换器 | `followers:size > 1000` |

#### size 转换器

获取集合、数组、Map 或字符串的长度/大小：

| 支持类型 | 说明 | 示例 |
|---------|------|------|
| `Collection` | 获取集合大小 | `followers:size > 1000` |
| `Map` | 获取 Map 大小 | `tags:size >= 5` |
| `Array` | 获取数组长度 | `ids:size == 3` |
| `CharSequence` | 获取字符串长度 | `username:size < 10` |

使用场景：
- "只有粉丝数大于 1000 的用户开启" → `followers:size > 1000`
- "只有订单项超过 5 个的订单开启" → `cartItems:size >= 5`
- "用户名长度小于 4 位的用户禁止" → `username:size < 4`

### 概率与分流 (Prob / Hash)

| 操作符 | 说明 | 示例 |
|------|------|------|
| `prob` | 随机概率：基于随机数，每次匹配结果可能不同 | `it prob 0.3` (30% 概率命中) |
| `hash` | 一致性 Hash：基于 MurmurHash64 算法，保证同一 Subject 的匹配结果幂等 | `userId hash 0.2` (固定 20% 的用户群体命中) |

> prob 说明：生成 0.0~1.0 的随机数，若小于等于阈值则命中。
>
> hash 说明：对输入值计算 Hash 并映射到 [0,1] 区间，相同输入始终得到相同结果。
> - 算法：采用高性能的 MurmurHash64。
> - 盐值 (Salt)：支持在 `MatchContext` 中通过 `setAttribute("salt", "xxx")` 设置盐值。不同开关使用不同的盐值可确保分流结果正交（打散更均匀），避免“同一批人命中所有测试”。


---

## 高级进阶

### 安全与容错模式 (Strict Mode)

业务生产环境中，避免单条规则报错导致整个请求挂掉是极其核心的要求。

* 默认安全模式：引擎默认处于安全模式。当发生空指针、类型转换异常或缺少属性时，引擎会打印错误日志并安全地返回 `false`，不会抛出异常阻断主流程。
* 严格模式：在开发测试或必须强一致性拦截的场景下，可以随时开启严格模式，此时引擎一旦出现异常逻辑将直接抛出 `CriterionEvaluationException`。
```java
MatchContext context = MatchContext.of(user).withStrictMode(true);
```

### 属性延迟加载 (Lazy Resolver)

配合逻辑短路特性（`&&`），如果前面的条件不满足，后续变量的求值将被跳过。你可以使用 `setLazyResolver` 实现变量的按需/延迟加载（例如懒加载查 DB/RPC），极大降低不必要的外部调用性能开销。

#### 方式一：使用 `LazyAttributeResolver` 注册表（推荐）

`LazyAttributeResolver` 是框架内置的注册表工具类，提供**声明式、链式调用**的 API，彻底告别 `if-else` 判断。每个 Key 对应一个独立的加载逻辑，互不干扰，结构清晰。

```java
User user = new User(1005, "李四");
MatchContext context = MatchContext.of(user);

// 方式 A：Supplier（不需要感知上下文时的简便写法）
LazyAttributeResolver resolver = new LazyAttributeResolver()
    .register("inWhitelist", () -> checkRedis(user.getId()))
    .register("riskScore",   () -> fetchRiskScoreFromApi(user.getId()))
    // 方式 B：AttributeResolver（可读取上下文数据的完整写法）
    .register("userTags",    (ctx, key) -> loadTagsFromDb(ctx.<User>getActual().getId()));

context.setLazyResolver(resolver);

// 执行匹配：age > 18 && $inWhitelist == true && $riskScore < 50
criteria.matches("age > 18 && $inWhitelist == true && $riskScore < 50", context);
```

#### 方式二：直接实现 `AttributeResolver` 接口（灵活写法）

如需统一处理多个 key，可直接实现 `AttributeResolver` 接口，入参包含完整的 `MatchContext`：

```java
MatchContext context = MatchContext.of(userId);
context.setLazyResolver((ctx, key) -> {
    if ("whitelist".equals(key)) {
        Object actualId = ctx.getActual();
        boolean isMember = checkRedis(actualId);
        // 返回"伪集合"：引擎只需 O(1) 开销即可完成匹配
        return isMember ? Collections.singletonList(actualId) : Collections.emptyList();
    }
    return null;
});
```


### Context属性共享与高级访问

* 变量提取：通过 `criteria.getVariables(expression)` 可以静态分析出表达式中引用的所有变量名。
* 嵌套对象防护：支持 `address.zipCode` 风格的嵌套访问，内置空指针安全保护，不怕对象链级联空指针。
* withActual 共享复用：如果想让同一个规则测试上下文中不同的对象源，可以使用 `MatchContext#withActual()` 方法创建新上下文，它将无缝共享原上下文的全局属性, 但拥有新的目标对象。
* 属性提取与默认值：通过 `getAttribute(key, defaultValue)` 在属性不存在时返回预设值。

## 自定义扩展 (SPI)

自定义实例通过 Builder API 创建：

| Builder 方法 | 说明 |
|------|------|
| `addOperator(name, logic)` | 添加自定义操作符 |
| `addValueConverter(converter)` | 添加值转换器 |
| `addSyntaxHandler(handler)` | 添加语法处理器 |
| `addCompiler(compiler)` | 添加自定义编译器 |
| `build()` | 构建不可变的 Criteria 实例 |

```java
// 不同业务线独立定制 Criteria，互不污染，确保绝对隔离：
Criteria customCriteria = Criteria.builder()
        .addOperator("intersects", new IntersectsOperator()) // 轻量增加特有算子
        .build();
```

## 架构与原理

### 核心执行流程
整个引擎通过 JIT 的思路将表达式一次编译，无限次极速复用：

`词法树解析 (Parser)` ➔ ` AST 预编译生成闭包 (Visitor+Compiler)` ➔ ` 高效复用执行 (Predicate.test)`

<details>
<summary>👉 点击查看详细的执行步骤与内部流转细节</summary>

```text
┌─────────────────────────────────────────────────────────────────┐
│  1. 用户调用 criteria.matches("age > 18", user)                 │
└─────────────────────────────────────────────────────────────────┘
                              │  LazyFunction 检查缓存未命中
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  2. 解析阶段 (Parser)                 词法划分与构建表达式树 AST   │
│     结果：PropertyCriterion("age", SmartCompareCriterion(">", 18))│
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  3. 编译阶段 (CompilingVisitor)       深度优化、常量预热并直出闭包 │
│     > 操作符 ">" 解析为 IntPredicate                               │
│     > 静态值 18 预解析加载为 Long (FastNumberUtil)                 │
│     > 返回 MatchPredicate 闭包函数并写入缓存池                       │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│  4. 运行阶段缓存极速获取：predicate.test(MatchContext)             │
│     > 触发内部原生核心方法: Long.compare(20, 18) > 0 → true       │
└─────────────────────────────────────────────────────────────────┘
```
</details>

<details>
<summary>👉 点击查看核心组件清单与底层类对应关系</summary>

| 组件 | 说明 | 包路径 |
|------|------|--------|
| `Criteria` | 门面类（不可变），提供表达式解析、编译、匹配及缓存功能 | `org.team4u.base.criterion` |
| `CriteriaBuilder` | 建造者类，用于组装自定义配置的 Criteria 实例 | `org.team4u.base.criterion` |
| `MatchContext` | 匹配上下文，持有实际值和属性 Map | `org.team4u.base.criterion.spi` |
| `AttributeResolver` | 属性解析器接口（函数式），入参含上下文和 key，用于延迟加载外部属性 | `org.team4u.base.criterion.spi` |
| `LazyAttributeResolver` | 内置解析器注册表，支持以声明式链式 API 注册多个 key 的延迟加载逻辑 | `org.team4u.base.criterion.spi` |
| `MatchPredicate` | 匹配断言函数接口（函数式接口） | `org.team4u.base.criterion.spi` |
| `Criterion` | 规则表达式 AST 节点根类 (抽象类)，持有原始表达式字符串 | `org.team4u.base.criterion.core` |
| `CriterionParser` | 解析器接口，将字符串解析为 AST | `org.team4u.base.criterion.parser` |
| `StandardCriterionParser` | 标准解析器实现，基于责任链模式 | `org.team4u.base.criterion.parser.impl` |
| `SyntaxHandler` | 语法处理器接口，支持优先级排序 | `org.team4u.base.criterion.parser` |
| `CriterionCompiler` | 编译器接口，将 AST 编译为 `MatchPredicate` | `org.team4u.base.criterion.spi` |
| `CompilingVisitor` | 编译访问者，协调编译器完成 AST 到函数的转换 | `org.team4u.base.criterion.visitor` |
| `CompilerRegistry` | 编译器注册表，自动扫描并管理 `CriterionCompiler` 实现 | `org.team4u.base.criterion.spi` |
| `ValueConverter` | 值转换器接口，将任意对象转换为 `Comparable` | `org.team4u.base.criterion.core.convert` |
| `ValueConverterRegistry` | 值转换器注册表，自动扫描并管理 `ValueConverter` 实现 | `org.team4u.base.criterion.core.convert` |
| `Value<T>` | 值提供者接口，屏蔽字面量和变量的区别 | `org.team4u.base.criterion.core.value` |
| `VariableExtractor` | 变量提取器，静态分析表达式中的变量名 | `org.team4u.base.criterion.visitor` |
| `CompareOperators` | 比较操作符工具类，提供统一的比较逻辑映射 | `org.team4u.base.criterion.core` |
| `FastNumberUtil` | 极速数字处理工具，使用原生类型避免 BigDecimal 开销 | `org.team4u.base.criterion.util` |
</details>

### Value 系统详解

`Value<T>` 接口统一屏蔽"字面量"和"变量"的区别：

| 实现类                | 说明                                                           | 使用场景                                      |
|--------------------|--------------------------------------------------------------|-------------------------------------------|
| `FixedValue<T>`    | 静态值，解析时确定值，运行时直接返回                                           | `'admin'`, `ACTIVE`, `18`, `true`, `null` |
| `VariableValue<T>` | 动态变量，解析时存储剥离了 `$` 的变量名，运行时从 `MatchContext.getAttribute()` 获取 | `$minAge`, `$threshold`                   |

`ValueFactory` 自动判断 Token 类型：

```java
// 内部实现逻辑
public static <T> Value<T> create(String token, Function<String, T> converter, Class<T> type) {
    if (token.startsWith("$")) {  // 强制 $ 前缀识别变量
        return new VariableValue<>(token.substring(1), type);
    } else {  // 字面量 (含无引号字符串)
        return new FixedValue<>(converter.apply(token));
    }
}
```

| Token 类型 | 示例                      | 判断规则        | 结果                       |
|----------|-------------------------|-------------|--------------------------|
| 变量引用     | `$minAge`               | 以 `$` 开头    | `VariableValue<T>`       |
| 带引号字符串   | `'active'`              | 以 `'` 开头和结尾 | `FixedValue<String>`     |
| 纯数字      | `18`, `3.14`            | 符合数字格式      | `FixedValue<BigDecimal>` |
| 特殊常量     | `null`, `true`, `false` | 关键字匹配       | `FixedValue<Object>`     |
| 无引号字符串   | `ACTIVE`, `VIP`         | 非上述情况的普通单词  | `FixedValue<String>`     |


### 内置组件总览

#### 内置 SyntaxHandler（按优先级排序）

| 处理器 | 优先级 | 功能 | 示例 |
|--------|--------|------|------|
| 自定义 Handler | 默认 0（可自定义） | 用户通过 `addSyntaxHandler()` 添加 | 自定义算子 |
| `SimplifySyntaxHandler` | 0 (默认) | 极简语法糖：`18` → `it == 18` | `18`, `'admin'` |
| `ValueConverterSyntaxHandler` | 0 (默认) | 值转换器语法：`subject:converter` | `createTime:date > '2023-01-01'` |
| `IsSyntaxHandler` | 0 (默认) | Is 语法：空值检查 | `name is null`, `name is not empty` |
| `BetweenSyntaxHandler` | 0 (默认) | 区间语法 | `age between [18, 30]` |
| `InSyntaxHandler` | 0 (默认) | In 语法：集合包含 | `id in [1, 2, 3]` |
| `ContainsSyntaxHandler` | 0 (默认) | Contains 语法：包含元素 | `roles contains 'admin'` |
| `ContainsAnySyntaxHandler` | 0 (默认) | ContainsAny 语法：交集检查 | `tags containsAny ['VIP', 'KOL']` |
| `ContainsAllSyntaxHandler` | 0 (默认) | ContainsAll 语法：全集包含 | `userTags containsAll ['VIP', 'KOL']` |
| `RegexSyntaxHandler` | 0 (默认) | 正则匹配 | `email =~ '.*@example\\.com$'` |
| `WildcardSyntaxHandler` | 0 (默认) | 通配符匹配 | `name like 'J*'` |
| `ProbabilitySyntaxHandler` | 0 (默认) | 随机概率 | `it prob 0.3` |
| `HashProbabilitySyntaxHandler` | 0 (默认) | Hash 分流 | `userId hash 0.2` |
| `DynamicSyntaxHandler` | 0 (默认) | 动态操作符（支持 `addOperator()` 扩展） | 自定义算子 |
| `RelationalOperatorSyntaxHandler` | `LOWEST_PRECEDENCE` | 数值/字符串比较（兜底） | `age > 18`, `name == 'admin'` |

> 处理流程：表达式按优先级顺序经过每个 Handler（优先级越高越先执行），第一个成功匹配的 Handler 返回 Criterion，后续 Handler 不再执行。
>
> 自定义 Handler 可通过重写 `priority()` 方法返回负数（如 `-100`）来获得更高优先级。

#### 内置 ValueConverter

| 转换器 ID | 实现类 | 功能 | 支持的输入 |
|---------|--------|------|-----------|
| `date` | `DateValueConverter` | 日期转换，支持 `now` 关键字 | `Date`、日期字符串、`"now"` |
| `version` | `VersionValueConverter` | 语义化版本号转换（基于 Hutool） | 版本字符串（如 `"1.2.3"`） |
| `number` | `NumberValueConverter` | 数值转换（使用 `FastNumberUtil` 转为 `Long`/`Double`） | `Number`、数字字符串 |
| `string` | `StringValueConverter` | 字符串转换 | 任意对象（调用 `toString()`） |
| `size` | `SizeValueConverter` | 大小/长度转换 | `Collection`、`Map`、`Array`、`CharSequence` |

DateValueConverter 实现细节：

```java
public Comparable<?> apply(Object obj) {
    if (obj == null) return null;
    if (obj instanceof Date) return (Date) obj;
    if ("now".equalsIgnoreCase(String.valueOf(obj))) return new Date();
    try {
        return DateUtil.parse(String.valueOf(obj));
    } catch (Exception e) {
        return null;
    }
}
```

VersionValueConverter 实现细节：

使用内部类 `ComparableVersion` 包装版本字符串，通过 `StrUtil.compareVersion()` 实现语义化版本比较。

#### 内置 Criterion 类型

所有 Criterion 都统一使用 `Compiler` 模式，在编译期进行预处理优化：

| Criterion | 说明 | 编译器优化点 |
|-----------|------|-------------|
| `LogicCriterion` | 逻辑组合（AND/OR） | 预编译子节点为数组，短路求值 |
| `PropertyCriterion` | 属性提取（嵌套访问） | 使用 `BeanUtil.getProperty` |
| `NumberCriterion` | 数值比较（`>`, `<`, `==` 等） | 使用 `FastNumberUtil` 零 GC 路径 |
| `StringCriterion` | 字符串比较 | 兜底逻辑 |
| `ComparableCriterion` | 通用 `Comparable` 比较 | 数值类型使用 `FastNumberUtil` |
| `SmartCompareCriterion` | 智能比较（自动类型推断） | 编译期生成 Long/Double/String 专用闭包 |
| `InCriterion` | 集合包含（`in`） | 编译期分拣为 longSet/doubleSet/stringSet/otherSet |
| `BetweenCriterion` | 区间判断（`between`） | 编译期预处理固定边界值 |
| `ContainsCriterion` | 包含元素（`contains`） | 支持数组/集合/字符串 |
| `ContainsAnyCriterion` | 交集检查（`containsAny`） | 编译期优化静态集合，支持动态变量 |
| `ContainsAllCriterion` | 全集包含（`containsAll`） | 编译期优化静态集合，支持动态变量 |
| `NullCriterion` | 空值检查（`is null/empty`） | - |
| `RegexCriterion` | 正则匹配（`=~`） | 编译期捕获 `Pattern` 引用 |
| `WildcardCriterion` | 通配符匹配（`like`） | 使用 `AntPathMatcher` |
| `ProbabilityCriterion` | 随机概率（`prob`） | 编译期提取固定阈值 |
| `HashProbabilityCriterion` | Hash 分流（`hash`） | 编译期提取固定阈值，MurmurHash64 算法 |
| `DynamicCriterion` | 动态自定义算子 | - |

---

## 自定义扩展

### 自定义类型比较 (ValueConverterRegistry)

如果您需要支持某种特殊类型的比较（如 `Money`, `Duration` 或自定义包装类），只需实现 `Comparable` 接口，并通过
`ValueConverterRegistry` 注册对应的 `ValueConverter`。

#### 实现自定义 Comparable 类型（可选）

```java
public class Money implements Comparable<Money> {
    private final BigDecimal amount;

    public Money(BigDecimal amount) {
        this.amount = amount;
    }

    @Override
    public int compareTo(Money other) {
        return this.amount.compareTo(other.amount);
    }
}
```

#### 注册转换器

实现 `ValueConverter` 接口，并通过 `CriteriaBuilder` 注册。

```java
// 定义自定义转换器
public class MoneyValueConverter implements ValueConverter {
    @Override
    public String id() {
        return "money";
    }

    @Override
    public Comparable<?> apply(Object obj) {
        if (obj == null) return null;
        return new Money(new BigDecimal(obj.toString()));
    }
}

// 通过 Builder 注册转换器
Criteria criteria = Criteria.builder()
        .addValueConverter(new MoneyValueConverter())
        .build();

// 使用自定义转换器
boolean match = criteria.matches("price:money > '100.00'", product);
```

### 轻量扩展 (addOperator)

如果您只需要扩展一个新的比较符号（算子），而不需要改变语法结构（依然遵循 `subject operator value` 模式），使用 `CriteriaBuilder.addOperator()`
是最快的方式。

```java
Criteria criteria = Criteria.builder()
        // 注册自定义算子：约等于
        .addOperator("~=", (actual, expected) -> {
            if (actual == null || expected == null) return false;
            return Math.abs(Double.parseDouble(actual.toString()) -
                    Double.parseDouble(expected.toString())) < 0.01;
        })
        .build();

// 使用自定义算子
boolean match = criteria.matches("price ~= 100", 100.005); // true
```

> 注意：`DynamicSyntaxHandler` 使用 `TreeMap<String, BiPredicate>` 存储算子，支持忽略大小写的算子匹配。

### 深度定制 (SPI 扩展)

当内置操作符无法满足需求时（例如需要全新的语法结构，如 `it has_role 'ADMIN'`），您可以按照以下步骤实现高扩展性的定制开发。

#### 1. 定义 Criterion (数据模型)

```java
public record HasRoleCriterion(Value<String> role) extends Criterion implements ValueContainer {
  @Override
  public List<Value<?>> values() {
    return Collections.singletonList(role);
  }
}
```
> 实现 `ValueContainer` 接口即可支持 `VariableExtractor` 自动提取其中的动态变量。

#### 2. 实现 CriterionCompiler (编译逻辑)

```java
public class HasRoleCompiler implements CriterionCompiler<HasRoleCriterion> {
    @Override
    public MatchPredicate compile(HasRoleCriterion criterion, CriterionVisitor<MatchPredicate> visitor) {
        Value<String> roleProvider = criterion.getRole();
        // 编译为高性能闭包函数直出
        return context -> {
            Object actual = context.getActual();
            if (!(actual instanceof User u)) return false;
            return u.hasRole(roleProvider.get(context));
        };
    }

    @Override
    public boolean supports(Class<? extends Criterion> clazz) {
        return HasRoleCriterion.class.isAssignableFrom(clazz);
    }
}
```

#### 3. 实现 SyntaxHandler (语法解析)

```java
public class HasRoleSyntaxHandler implements SyntaxHandler {
    @Override
    public int priority() {
        return -100; // 提升优先级
    }

    @Override
    public Criterion tryParse(String subject, CriterionParser.Context context) {
        if (!context.match("has_role")) return null;
        var roleValue = context.consumeAsValue(String.class, s -> s);
        return context.wrapProperty(subject, new HasRoleCriterion(roleValue));
    }
}
```

#### 4. 全流程集成

```java
Criteria criteria = Criteria.builder()
        .addSyntaxHandler(new HasRoleSyntaxHandler())
        .addCompiler(new HasRoleCompiler())
        .build();

CurrentUser currentUser = new CurrentUser("ADMIN", "USER");
boolean match = criteria.matches("it has_role 'ADMIN'", currentUser); // true
```