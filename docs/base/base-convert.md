# 类型转换器体系 (ConvertUtil)

`ConvertUtil` 与 `TypeConverterRegistry` 提供了覆盖基础标量、时间日期、集合、数组、枚举以及 JavaBean 的全类型安全转换能力。

---

## 核心转换器注册表 (`TypeConverterRegistry`)

`TypeConverterRegistry` 内置了以下有序转换器链条（按 `order()` 升序执行匹配）：

| 转换器类名 | 优先级 Order | 支持的目标类型与特征 |
| :--- | :--- | :--- |
| **`ScalarTypeConverter`** | `10` | 基本类型及其包装类（`int`, `long`, `boolean`, `double`, `float`, `short`, `byte`, `char`）、`String`、`BigDecimal`、`BigInteger`、`Number` |
| **`EnumTypeConverter`** | `20` | 枚举类型转换（支持按枚举名称大小写不敏感匹配） |
| **`TemporalTypeConverter`** | `30` | 时间类型转换（`Date`, `LocalDate`, `LocalDateTime`, `Instant`），支持时间戳与常见日期格式化字符串 |
| **`CollectionTypeConverter`** | `40` | `List`, `Set`, `Queue`, `Collection` 转换（支持将逗号分隔的字符串自动切分为集合元素） |
| **`ArrayTypeConverter`** | `50` | 各类基本类型及对象数组转换 |
| **`BeanTypeConverter`** | `60` | 将 `Map` 自动通过 `BeanUtil.toBean` 转换为目标 JavaBean 对象 |

---

## 核心 API 清单 (`ConvertUtil`)

> [!IMPORTANT]
> 注意 `ConvertUtil.convert` 的参数顺序：**目标类型在前，源数据在后**！

### 通用强类型转换
```java
// 基础转换（转换失败返回 null）
<T> T convert(Class<T> type, Object value);
<T> T convert(Type type, Object value);

// 带默认值的安全转换
<T> T convert(Class<T> type, Object value, T defaultValue);
<T> T convert(Type type, Object value, T defaultValue);
```

### 标量便捷转换方法
| 方法签名 | 说明 |
| :--- | :--- |
| `String toStr(Object value, [String defaultValue])` | 转为 String 字符串 |
| `Long toLong(Object value, [Long defaultValue])` | 转为 Long 类型（支持 Number、字符串解析） |
| `Integer toInt(Object value, [Integer defaultValue])` | 转为 Integer 类型 |
| `Double toDouble(Object value, [Double defaultValue])` | 转为 Double 类型 |
| `Float toFloat(Object value, [Float defaultValue])` | 转为 Float 类型 |
| `Short toShort(Object value, [Short defaultValue])` | 转为 Short 类型 |
| `Byte toByte(Object value, [Byte defaultValue])` | 转为 Byte 类型 |
| `BigDecimal toBigDecimal(Object value, [BigDecimal defaultValue])` | 转为高精度 BigDecimal 类型 |
| `BigInteger toBigInteger(Object value, [BigInteger defaultValue])` | 转为大整数 BigInteger 类型 |
| `Character toChar(Object value, [Character defaultValue])` | 转为 Character 字符 |
| `Boolean toBool(Object value, [Boolean defaultValue])` | 转为 Boolean（支持 `"true"`, `"1"`, `"yes"`, `"ok"`, `"on"`, `"y"` 识别为 `true`；`"false"`, `"0"`, `"no"`, `"off"`, `"n"` 识别为 `false`） |

### 集合与数组转换方法
```java
// 转为通用 List
<T> List<T> toList(Object value);

// 转为指定元素类型的 List
<T> List<T> toList(Object value, Class<T> elementType);

// 转为指定组件类型的数组
<T> Object toArray(Class<T> componentType, Object value);
```

---

## 使用示例

```java
import com.team4u.framework.base.convert.ConvertUtil;
import java.time.LocalDateTime;
import java.util.List;

// 1. 基础标量
Integer port = ConvertUtil.convert(Integer.class, "8080");
Boolean active = ConvertUtil.toBool("yes"); // true

// 2. 日期时间解析
LocalDateTime time = ConvertUtil.convert(LocalDateTime.class, "2026-08-25 15:30:00");

// 3. 逗号分隔字符串转类型化 List
List<Long> idList = ConvertUtil.toList("101,102,103", Long.class);

// 4. 枚举大小写不敏感转换
OrderStatus status = ConvertUtil.convert(OrderStatus.class, "paid");
```

---

## 扩展自定义类型转换器 (`TypeConverter`)

```java
import com.team4u.framework.base.convert.TypeConverter;
import com.team4u.framework.base.convert.ConvertUtil;
import java.lang.reflect.Type;

public class MyCustomConverter implements TypeConverter {

    @Override
    public boolean supports(Type targetType, Object source) {
        return targetType == CustomVo.class && source instanceof String;
    }

    @Override
    public Object convert(Type targetType, Object source) {
        return CustomVo.parse((String) source);
    }

    @Override
    public int order() {
        return 5; // 优先级高于内置转换器
    }
}

// 动态注册到全局注册表
ConvertUtil.registerConverter(new MyCustomConverter());
```
