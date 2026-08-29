# 快速开始

本文介绍如何在 3 分钟内快速使用 `team4u-mask`。

---

## 引入依赖

核心脱敏引入 `team4u-mask`（传递 `team4u-base` 与 `team4u-policy`）：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-mask</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

需要 Jackson 自动脱敏或配置中心动态规则时，分别显式添加：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-mask-jackson</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-mask-config</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`team4u-mask-config` 只包含 JSON 解析 API；应用还需提供 `team4u-serializer-jackson` 或自定义 `JsonSerializerPolicy`。

---

## 编程式脱敏（一行代码）

通过 `FastMasker` 静态门面快速执行脱敏：

```java
import com.team4u.framework.mask.FastMasker;
import com.team4u.framework.mask.MaskType;

public class MaskQuickStart {

    public static void main(String[] args) {
        // 手机号脱敏（保留前3后3）
        String mobile = FastMasker.mask("13812345678", MaskType.MOBILE);
        System.out.println(mobile); // 输出: 138*****678

        // 中文姓名脱敏（<=3字保留末尾1字）
        String name = FastMasker.mask("周杰伦", MaskType.NAME);
        System.out.println(name); // 输出: **伦

        // 身份证脱敏（保留前5后2）
        String idCard = FastMasker.mask("440111199001011234", MaskType.ID_CARD_NO);
        System.out.println(idCard); // 输出: 44011***********34

        // 银行卡号脱敏（保留前4后2）
        String bankCard = FastMasker.mask("6222020212345678", MaskType.BANK_CARD_NO);
        System.out.println(bankCard); // 输出: 6222**********78

        // 电子邮箱脱敏（@前保留首字符）
        String email = FastMasker.mask("jay.chou@gmail.com", MaskType.EMAIL);
        System.out.println(email); // 输出: j****@gmail.com
    }
}
```

---

## 注解式脱敏与 Jackson JSON 序列化

### 步骤 1：在 JavaBean 字段上标注 `@Mask`

```java
import com.team4u.framework.mask.Mask;
import com.team4u.framework.mask.MaskType;
import lombok.Data;

@Data
public class UserDto {
    private Long id;

    @Mask(MaskType.NAME)
    private String realName;

    @Mask(MaskType.MOBILE)
    private String mobile;

    @Mask(MaskType.ID_CARD_NO)
    private String idCardNo;
}
```

### 步骤 2：用 `MaskedJson` 序列化（观测向显式脱敏）

**契约**：全局 `JsonUtil` / 共享 `ObjectMapper` 奉行「永远无损」——存库、缓存、重放载荷等存储向序列化必须拿到原文明文，脱敏模块**不注册全局**（否则 `@Mask` 字段会被静默写成掩码串，反序列化无任何报错信号）。需要脱敏输出（日志、审计、对外展示）时，显式使用 mask 模块的门面：

```java
import com.team4u.framework.mask.jackson.MaskedJson;
import com.team4u.framework.serializer.json.JsonUtil;

// 存数据库：永远明文（物理上不可能脱敏）
String plain = JsonUtil.toJsonStr(user);

// 打日志 / 对外输出：显式声明观测语义，输出脱敏 JSON
String masked = MaskedJson.toJsonStr(user);
// {"id":1001,"realName":"周杰伦","mobile":"138*****678","idCardNo":"4401**********34"}
```

自建独立 `ObjectMapper` 的场景，手工注册脱敏模块：

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team4u.framework.mask.jackson.JacksonMaskModule;

public class JacksonMaskQuickStart {

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        
        // 注册通用脱敏模块
        mapper.registerModule(new JacksonMaskModule());

        UserDto user = new UserDto();
        user.setId(1001L);
        user.setRealName("周杰伦");
        user.setMobile("13812345678");
        user.setIdCardNo("440111199001011234");

        // 序列化为 JSON 时自动应用脱敏规则
        String json = mapper.writeValueAsString(user);
        System.out.println(json);
        // 输出: {"id":1001,"realName":"**伦","mobile":"138*****678","idCardNo":"44011***********34"}

        // 内存中 Java 对象字段的真实值完全不受影响
        System.out.println("内存真实手机号: " + user.getMobile()); // 13812345678
    }
}
```

---

## 下一步

- 查看所有 15 种内置脱敏算法与算法细节：[内置脱敏算法与类型](mask-types.md)
- 了解 Jackson 序列化修饰器与 Map 脱敏机制：[注解式脱敏与 Jackson 集成](mask-annotation.md)
- 开启配置中心动态规则下发：[动态规则与配置驱动](mask-dynamic.md)
- 扩展自定义脱敏算法与 Unicode 安全：[扩展机制与 Unicode 安全](mask-extension.md)
- 查看 HTTP 出参保护与日志实战：[实战案例](mask-sample.md)
