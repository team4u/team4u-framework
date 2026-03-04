[返回总目录](../README.md)

# 脱敏管理模块

[![JDK 8+](https://img.shields.io/badge/JDK-8+-green.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

## 目录

- [简介](#简介)
- [快速入门](#快速入门)
- [核心特性](#核心特性)
- [编程式脱敏](#编程式脱敏)
- [注解式脱敏](#注解式脱敏)
- [动态规则与配置中心](#动态规则与配置中心)
- [Jackson 无侵入脱敏](#jackson-无侵入脱敏)
- [SPI 扩展](#spi-扩展)
- [典型场景](#典型场景)
- [架构与原理](#架构与原理)

---

## 简介

`team4u-mask` 是一个轻量级、高性能、可扩展的 Java 脱敏模块，提供统一的敏感字段保护能力。

它支持三类能力协同工作：

1. **编程式脱敏**：直接调用 `FastMasker` 完成字符串脱敏。
2. **注解式脱敏**：基于 `@Mask` 注解声明字段脱敏规则。
3. **配置驱动脱敏**：通过 `team4u.mask.rules` 动态规则为第三方类/Map 提供无侵入脱敏。

模块内部基于 [team4u-policy](../team4u-policy/README.md) 的 `KeyedPolicyRegistry` 实现策略路由，内置标准算法，并支持 SPI 与编程式扩展。

### 核心优势

* **高性能路由**：策略预注册 + Key 直达匹配，核心路径无反射、无正则。
* **动态可控**：对接 [team4u-config](../team4u-config/README.md)，支持配置热更新。
* **零侵入集成**：通过 `JacksonMaskModule` 自动接管对象/Map 序列化脱敏。
* **扩展友好**：支持 `FastMasker.register(...)` 与 SPI 双通道扩展。
* **统一治理**：注解规则与外部规则可并行使用，满足“代码内定义 + 平台统一治理”两类诉求。

---

## 快速入门

### 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-mask</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

> 说明：
> - 若你需要动态配置规则，请确保引入并初始化 `team4u-config`。
> - 若你需要 JSON 自动脱敏，请引入 Jackson 并注册 `JacksonMaskModule`。

### 最小可用示例

```java
import com.team4u.framework.mask.FastMasker;
import com.team4u.framework.mask.MaskType;

String mobile = FastMasker.mask("13812345678", MaskType.MOBILE);   // 138*****678
String email = FastMasker.mask("fjayy@gmail.com", MaskType.EMAIL); // f****@gmail.com
String name = FastMasker.mask("周杰伦", MaskType.NAME);              // **伦
```

---

## 核心特性

### 1. 内置脱敏类型

`MaskType` 内置以下标准策略：

| 类型 | 说明 |
| :--- | :--- |
| `NAME` | 姓名脱敏（中文保留尾部，英文保留首尾） |
| `MOBILE` | 手机号（保留前 3 后 3） |
| `BANK_CARD_NO` | 银行卡号（保留前 4 后 2） |
| `ID_CARD_NO` | 身份证号（保留前 5 后 2） |
| `B1A1` | 保留前 1 后 1 |
| `B2A2` | 保留前 2 后 2 |
| `PERCENT66` | 居中掩码约 66% |
| `PERCENT66_LIMIT10` | 66% 掩码后最多显示 10 字符 |
| `PERCENT1_LIMIT200` | 1% 掩码后最多显示 200 字符 |
| `ADDRESS` | 地址（保留前 9） |
| `EMAIL` | 邮箱脱敏 |
| `NONE` | 不脱敏（原样返回） |
| `HIDE` | 固定返回 `*` |
| `NULL` | 固定返回 `null` |
| `PASSWORD` | 固定返回 `******` |

### 2. 行为约定

* 输入值为 `null` 或空串时：默认原样返回（按具体策略处理）。
* 未找到策略 Key 时：`FastMasker` 返回原值，不抛异常。
* 所有策略统一通过 `MaskPolicy#key()` 路由，便于标准化治理。

---

## 编程式脱敏

### 1. 使用标准枚举

```java
String idCard = FastMasker.mask("440111199001011234", MaskType.ID_CARD_NO); // 44011***********34
String bank = FastMasker.mask("6222123456789011", MaskType.BANK_CARD_NO);    // 6222**********11
```

### 2. 使用动态字符串 Key

适合与平台配置联动，或使用自定义策略标识。

```java
String masked = FastMasker.mask("A123456789", "PASSPORT");
```

### 3. 注册自定义策略

```java
import com.team4u.framework.mask.MaskPolicy;
import com.team4u.framework.mask.MaskUtils;
import com.team4u.framework.mask.FastMasker;

public class PassportMaskPolicy implements MaskPolicy {
    @Override
    public String key() {
        return "PASSPORT";
    }

    @Override
    public String mask(String value) {
        return MaskUtils.mask(value, 2, 2);
    }
}

FastMasker.register(new PassportMaskPolicy());
String result = FastMasker.mask("E123456789", "PASSPORT");
```

---

## 注解式脱敏

### 1. 标注字段

```java
import com.team4u.framework.mask.Mask;
import com.team4u.framework.mask.MaskType;

public class UserDTO {
    private String name;

    @Mask(MaskType.MOBILE)
    private String mobile;

    @Mask(MaskType.ID_CARD_NO)
    private String idCardNo;
}
```

### 2. 注册 Jackson 模块

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team4u.framework.mask.jackson.JacksonMaskModule;

ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new JacksonMaskModule());

String json = mapper.writeValueAsString(userDTO);
```

> `@Mask` 仅作用于字段序列化阶段，不影响对象内存中的原值。

---

## 动态规则与配置中心

除了注解，你还可以通过配置中心统一下发脱敏规则，实现对第三方类或动态结构的治理。

### 1. 启动规则仓库监听

```java
import com.team4u.framework.mask.MaskBootstrap;
import com.team4u.framework.config.core.ConfigManager;

ConfigManager configManager = ...;
MaskBootstrap.global().start(configManager);

// 应用关闭时可调用
// MaskBootstrap.global().stop();
```

### 2. 配置 Key 与数据结构

固定配置键：`team4u.mask.rules`

配置结构：`Map<className, Map<fieldName, maskTypeKey>>`

```json
{
  "*": {
    "mobile": "MOBILE",
    "idCardNo": "ID_CARD_NO"
  },
  "com.example.dto.PaymentDTO": {
    "bankCardNo": "BANK_CARD_NO",
    "receiverName": "NAME"
  },
  "java.util.HashMap": {
    "password": "PASSWORD"
  }
}
```

### 3. 匹配优先级

1. **类级精确匹配**：`com.example.dto.PaymentDTO.bankCardNo`
2. **全局规则兜底**：`*.mobile`

说明：若字段同时存在注解与外部规则，**注解优先级更高**。

---

## Jackson 无侵入脱敏

`JacksonMaskModule` 通过 `DynamicMaskSerializerModifier` 自动介入序列化流程：

1. 处理对象字段：先看 `@Mask`，再查 `MaskRuleRepository`。
2. 处理 `Map`：对 `String key + String value` 的项按规则脱敏。
3. 复杂值递归：Map 中非字符串值走 Jackson 默认序列化链。

### 序列化上下文配置（可选）

你可以通过 `MaskConfig` 控制字符串最大输出长度，避免超长字段穿透日志或响应。

```java
import com.team4u.framework.mask.jackson.MaskConfig;

MaskConfig config = new MaskConfig().setMaxStringLength(64);

String json = mapper.writer()
        .withAttribute(MaskConfig.ATTR_KEY, config)
        .writeValueAsString(payload);
```

超过长度时，输出格式类似：

```text
xxxxx... [Truncated len:1234]
```

---

## SPI 扩展

`FastMasker` 在静态初始化阶段会自动：

1. 扫描并注册内置策略。
2. 从 `ServiceLoader` 加载外部 `MaskPolicy`。

### 扩展步骤

1. 实现 `MaskPolicy`。
2. 在 `META-INF/services/com.team4u.framework.mask.MaskPolicy` 中注册实现类全名。
3. 在业务中直接使用 `FastMasker.mask(value, "YOUR_KEY")` 调用。

---

## 典型场景

### 场景 A：接口响应防泄漏

在 DTO 字段上标注 `@Mask`，保障出参序列化自动脱敏，防止上游误传明文。

### 场景 B：日志统一治理

通过 `MaskConfig.maxStringLength` + 动态规则，控制超长字段与敏感字段输出。

### 场景 C：第三方对象无侵入治理

无法修改源码的第三方类可通过 `team4u.mask.rules` 下发规则实现统一脱敏。

### 场景 D：策略按业务线扩展

不同业务线可实现各自 `MaskPolicy`（如证件、工号、设备标识），通过 SPI 自动接入。

---

## 架构与原理

### 核心执行流程

1. `FastMasker` 初始化：加载内置 + SPI 策略到 `KeyedPolicyRegistry`。
2. `MaskBootstrap` 启动：`MaskRuleRepository` 监听 `team4u.mask.rules`。
3. 序列化阶段：`JacksonMaskModule` 注入 `DynamicMaskSerializerModifier`。
4. 字段处理：按 **注解优先、规则兜底** 决定 `maskType`。
5. 策略执行：`FastMasker` 根据 `maskType` 路由到具体 `MaskPolicy`。

### 状态流转图

```mermaid
graph TD
    A[业务对象/Map] --> B[ObjectMapper + JacksonMaskModule]
    B --> C[DynamicMaskSerializerModifier]
    C --> D{@Mask 注解?}
    D -->|是| E[MaskStringSerializer]
    D -->|否| F[MaskRuleRepository 查规则]
    F --> E
    E --> G[FastMasker]
    G --> H[KeyedPolicyRegistry]
    H --> I[具体 MaskPolicy]
    I --> J[脱敏后输出]
```
