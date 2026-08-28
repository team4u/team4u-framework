# 扩展机制与 Unicode 安全

`team4u-mask` 支持自由扩展自定义脱敏算法；`team4u-mask-jackson` 额外内置针对大文本的超长截断保护，二者均基于 Unicode CodePoint 安全计算。

---

## 扩展自定义脱敏策略 (`MaskPolicy`)

所有脱敏算法均实现 `team4u-mask` 的 `MaskPolicy` 接口（继续继承 `team4u-policy` 的 `KeyedPolicy<String>`）。

### 步骤 1：编写自定义策略实现类
```java
import com.team4u.framework.mask.MaskPolicy;
import com.team4u.framework.mask.MaskUtils;

public class PassportMaskPolicy implements MaskPolicy {

    @Override
    public String key() {
        return "PASSPORT"; // 策略唯一路由标识
    }

    @Override
    public String mask(String value) {
        // 护照脱敏：保留前 2 字符与后 2 字符，中间掩码
        return MaskUtils.mask(value, 2, 2);
    }
}
```

### 步骤 2：注册策略到系统中
框架支持以下两种注册方式：

#### 方式 A：编程式注册
```java
import com.team4u.framework.mask.FastMasker;

FastMasker.register(new PassportMaskPolicy());

// 立即生效
String maskedPassport = FastMasker.mask("E12345678", "PASSPORT"); // E1*****78
```

#### 方式 B：Java 标准 SPI 自动装配
在项目 `src/main/resources/META-INF/services/com.team4u.framework.mask.MaskPolicy` 文件中添加实现类的全限定名：
```text
com.mycompany.mask.PassportMaskPolicy
```
在 `FastMasker` 类加载时，会通过 Java 标准 `ServiceLoader` 加载并注册该策略。

---

## 超长报文截断保护 (`MaskConfig`)

在生产环境的接口出参或日志打印中，某些字段可能包含大段 Base64 编码的图片或超长 XML/JSON 报文。为避免打爆磁盘日志或撑爆网关缓冲区，可通过 `MaskConfig` 配置字符串最大截断长度。

### 使用示例
```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team4u.framework.mask.jackson.JacksonMaskModule;
import com.team4u.framework.mask.jackson.MaskConfig;

ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new JacksonMaskModule());

// 创建并配置最大字符串长度为 32 个 CodePoint 字符
MaskConfig maskConfig = new MaskConfig().setMaxStringLength(32);

// 通过 ObjectWriter 传入序列化属性
String jsonOutput = mapper.writer()
        .withAttribute(MaskConfig.ATTR_KEY, maskConfig)
        .writeValueAsString(largePayloadObject);
```

### 截断输出效果
当字符串长度超过 32 字符时，自动截取前 32 个字符并追加原长度提示：
```text
"eyJhbGciOiJIUzI1NiIsInR5cCI6Ikp... [Truncated len: 2048]"
```

---

## Unicode CodePoint 安全计算原理

在 Java 中，一个 `char` 仅能表示 16 位 Unicode 字符（基本多语言平面 BMP）。对于包含 4 字节的表情符号或生僻字，Java 内部使用**代理对 (Surrogate Pair)** 表示，即占用 2 个 `char`。

若使用普通的 `String.length()` 或 `String.substring()` 进行截取或掩码，极易将代理对拆开，导致**乱码或前端解析崩溃**。

### `MaskUtils` 的安全算法实现

`MaskUtils` 内部完全基于 `Character.codePointCount` 与 `Character.offsetByCodePoints` 执行安全定位：

```java
public class MaskUtils {

    // 安全统计实际字符数量 (1 个多字节 CodePoint 计算为 1 个字符)
    public static int codePointLength(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return value.codePointCount(0, value.length());
    }

    // 安全按 CodePoint 截取子串，绝不拆散代理对
    public static String substringByCodePoints(String value, int begin, int end) {
        if (value == null) {
            return null;
        }
        int safeBegin = Math.max(0, begin);
        int safeEnd = Math.max(safeBegin, Math.min(end, codePointLength(value)));
        int beginIndex = value.offsetByCodePoints(0, safeBegin);
        int endIndex = value.offsetByCodePoints(0, safeEnd);
        return value.substring(beginIndex, endIndex);
    }
}
```

### 效果对比
```java
// 包含多字节字符的文本
String rawText = "\uD83D\uDE00\uD83D\uDE01\uD83D\uDE0213812345678";

// MaskUtils 准确识别前 3 个字符为多字节 CodePoint，保留前 3 个字符与后 3 位数字：
String safeResult = FastMasker.mask(rawText, MaskType.MOBILE);
System.out.println(safeResult); // 输出: \uD83D\uDE00\uD83D\uDE01\uD83D\uDE02*****678 (多字节字符完整无损)
```
