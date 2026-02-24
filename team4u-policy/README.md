# 策略模块

## 核心优势

- **极致性能**：核心注册表采用读写分离与无锁读取设计，关键路径达到 O(1) 查找复杂度，轻松应对高并发业务场景。
- **动态热加载**：内置高效的缓存与哈希比对机制，当配置文本（JSON/XML）未变更时完全跳过解析过程，实现毫秒级的策略动态更新。
- **即插即用**：支持 Java SPI 与类路径自动扫描，新策略只需实现接口即可自动生效，实现业务逻辑与管理框架的彻底解耦。
- **灵活的责任链**：支持基于上下文自动匹配与优先级排序，轻松处理复杂的条件过滤、风控拦截与优惠计算逻辑。
- **类型安全与扩展**：基于泛型设计，在编译期规避类型风险，同时提供高度抽象的接口，方便根据业务需求定制各类扩展策略。

## 为什么不直接使用 Map？

虽然简单的 `Map<String, Policy>` 也能实现查找，但 `KeyedPolicy` 提供了更高维度的抽象：

- **对象自描述**：在 Map 模式中，Key 与 Value 是分离的；而 `KeyedPolicy` 要求策略对象实现 `key()` 方法，确保“身份标识”是策略对象固有属性的一部分，便于对象在不同组件间流转而不会丢失路由信息。
- **极致的读优化**：原生 `ConcurrentHashMap` 虽然线程安全，但在超高并发下仍存在一定的锁竞争。该模块的注册表针对“一写多读”场景进行了专门优化，通过 Copy-On-Write 机制确保读取路径完全无锁，提供更稳定的响应时间。
- **零配置自动化**：使用 Map 需要手动维护注入逻辑。Policy 模块配合 `PolicyScanner` 可以实现“实现即注册”，支持从特定包路径或 SPI 配置文件自动加载实现类，极大地降低了配置维护成本。

## 核心架构概览

该模块主要由以下三种核心模式组成：

- 键值路由模式 (`KeyedPolicy`)：基于唯一 Key 进行 O(1) 复杂度的精准匹配，适用于支付渠道路由、指令分发等场景。
- 有序责任链模式 (`ContextPolicy`)：基于上下文匹配 (`supports`) 和优先级 (`priority`) 进行过滤和排序，适用于风控规则、优惠计算等场景。
- 动态策略模式 (`DynamicPolicyProvider`)：支持将 JSON/XML 等配置文本动态解析为策略对象，并提供高性能的缓存和变更检测机制。

---

## 场景一：精准路由 (Key-Value)

当你需要根据一个明确的标识（如 "ALIPAY", "WECHAT"）直接找到对应的处理器时使用此模式。

### 定义策略接口

继承 `KeyedPolicy<K>` 接口，其中 `K` 是路由键的类型（通常是 String 或 Integer）。

```java
import com.team4u.policy.KeyedPolicy;

// 定义支付策略接口
public interface PaymentPolicy extends KeyedPolicy<String> {
    void pay(double amount);
}

```

### 实现具体策略

实现 `key()` 方法返回唯一标识。

```java
public class AlipayPolicy implements PaymentPolicy {
    @Override
    public String key() {
        return "ALIPAY";
    }

    @Override
    public void pay(double amount) {
        System.out.println("Using Alipay: " + amount);
    }
}

```

### 注册与使用 (`KeyedPolicyRegistry`)

使用 `KeyedPolicyRegistry` 进行管理。该注册表针对读操作进行了极致优化（Copy-On-Write 机制），确保高并发下的读取性能。

```java
import com.team4u.policy.KeyedPolicyRegistry;

// 创建注册表
KeyedPolicyRegistry<String, PaymentPolicy> registry = new KeyedPolicyRegistry<>(PaymentPolicy.class);

// 注册策略
registry.register(new AlipayPolicy());

// 使用策略 (O(1) 查找)
registry.get("ALIPAY").ifPresent(policy -> policy.pay(100.0));

```

---

## 场景二：条件过滤与责任链 (Ordered Chain)

当你需要遍历一组策略，根据上下文决定是否执行，且策略之间有执行顺序时使用此模式。

### 定义策略接口

继承 `ContextPolicy<C>` 接口，其中 `C` 是上下文对象的类型。

```java
import com.team4u.policy.ContextPolicy;

// 定义优惠券策略
public interface DiscountPolicy extends ContextPolicy<OrderContext> {
    double calculate(OrderContext context);
}

```

### 实现具体策略

重写 `supports` (是否支持) 和 `priority` (执行顺序，值越小优先级越高)。

```java
public class VipDiscountPolicy implements DiscountPolicy {
    @Override
    public boolean supports(OrderContext context) {
        return context.isVip();
    }

    @Override
    public int priority() {
        return ContextPolicy.HIGH; // 高优先级
    }

    @Override
    public double calculate(OrderContext context) {
        return context.getPrice() * 0.8;
    }
}

```

### 注册与使用 (`OrderedPolicyChain`)

使用 `OrderedPolicyChain` 管理。它会自动根据 `priority` 对策略进行排序。

```java
import com.team4u.policy.OrderedPolicyChain;

// 创建链
OrderedPolicyChain<OrderContext, DiscountPolicy> chain = new OrderedPolicyChain<>(DiscountPolicy.class);

// 注册 (自动排序)
chain.register(new VipDiscountPolicy());
chain.register(new NormalPolicy());

// 获取所有匹配的策略
List<DiscountPolicy> matches = chain.allMatches(currentContext);

// 或者获取第一个匹配的策略
chain.firstMatch(currentContext).ifPresent(p -> ...);

```

### 高级用法：策略流水线 (`PolicyPipeline`)

如果你需要按顺序执行策略，并在某个策略返回 false 时中断流程（如风控拦截），可以使用 `PolicyPipeline`。

```java
PolicyPipeline<OrderContext> pipeline = new PolicyPipeline<>(chain);

pipeline.executeChain(context, (policy, ctx) -> {
    // 执行逻辑
    boolean pass = policy.check(ctx);
    // 返回 true 继续下一个策略，返回 false 中断流水线
    return pass;
});

```

---

## 场景三：动态配置热加载 (Dynamic Provider)

当策略逻辑需要根据配置文本（如数据库中的 JSON 字符串）动态生成，且需要高性能缓存和变更检测时使用。

### 核心组件

- ConfigParser: 将字符串解析为配置对象。
- PolicyFactory: 根据配置对象创建策略实例。
- DynamicPolicyProvider: 管理缓存、哈希比对和线程安全更新。

### 使用示例

假设我们需要根据一段 JSON 规则生成一个规则引擎策略：

```java
// 定义解析器 (String -> Config)
StringConfigParser<RuleConfig> parser = jsonStr -> JSON.parseObject(jsonStr, RuleConfig.class);

// 定义工厂 (Config -> Policy)
PolicyFactory<RuleConfig, RulePolicy> factory = (id, config) -> new ConcreteRulePolicy(config);

// 创建提供者 (LRU缓存容量 100)
DynamicPolicyProvider<String, RuleConfig, RulePolicy> provider = 
    DynamicPolicyProvider.createStringLru(100, parser, factory);

// 获取策略 (高性能)
// 逻辑：
// - 如果 jsonString 哈希未变，直接返回缓存策略 (无解析开销)
// - 如果哈希变化，解析 Config -> 创建 Policy -> 更新缓存
RulePolicy policy = provider.get("rule_id_1001", jsonStringFromDb);

```

性能优势： `DynamicPolicyProvider` 内部维护了 `inputHashCache`，在输入源（如配置字符串）未变更的情况下，完全跳过 JSON 解析和对象创建过程，性能比直接解析快几个数量级。

---

## 辅助功能：自动扫描与注册

为了避免手动 `new` 每一个策略，可以使用 `PolicyScanner` 进行包扫描或 SPI 加载。

### 包扫描注册

自动扫描指定包下所有实现了策略接口的类，并注册到 Registry 中。

```java
// 扫描 PaymentPolicy 所在包下的所有实现类并注册
PolicyScanner.scanAndRegister(registry);

// 或者指定具体包名
PolicyScanner.scanAndRegister(registry, "com.myapp.strategies", PaymentPolicy.class);

```

### ServiceLoader (SPI) 注册

基于 Java 标准的 SPI 机制加载。

```java
PolicyScanner.registerFromServiceLoader(registry);
```

配置步骤：

- 在项目的 `src/main/resources/` 目录下创建 `META-INF/services/` 文件夹。
- 创建一个以策略接口全限定名命名的文件（例如：`com.myapp.PaymentPolicy`）。
- 在该文件中写入具体实现类的全限定名，每行一个。

目录结构示例：

```text
src/main/resources/
└── META-INF/
    └── services/
        └── com.myapp.PaymentPolicy  <-- 文件名是接口的全限定名
```

文件内容示例 (`com.myapp.PaymentPolicy`)：

```text
com.myapp.impl.AlipayPolicy
com.myapp.impl.WechatPolicy
```


---

## API 速查表

| 组件 | 适用场景 | 关键特性 | 引用源 |
| --- | --- | --- | --- |
| KeyedPolicyRegistry | 明确 Key 的路由 (Map模式) | 读写分离，读取无锁，高性能 |  |
| OrderedPolicyChain | 需排序、条件过滤的链式处理 | 自动排序，volatile 读优化 |  |
| PolicyPipeline | 需中断控制的流程执行 | 封装了循环与回调逻辑 |  |
| DynamicPolicyProvider | 文本配置转策略对象 | 输入哈希比对，避免重复解析 |  |
| PolicyScanner | 策略自动发现 | 支持反射扫描与 SPI |  |

## 最佳实践

- 单例模式：`PolicyRegistry` 和 `DynamicPolicyProvider` 应当作为单例（Singleton）或 Spring Bean 管理，因为它们包含缓存。
- 异常处理：注册不同类型的策略到同一个 Registry 会抛出 `PolicyException`，请确保泛型类型匹配。
- 并发安全：所有的 Registry 实现都是线程安全的（Synchronized 写，Volatile/CopyOnWrite 读），可以放心地在多线程环境中使用。
