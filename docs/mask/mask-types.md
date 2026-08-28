# 内置脱敏算法与类型

`team4u-mask` 在 `MaskType` 枚举中预置了 15 种符合国家网络安全与个人信息保护法标准的标准脱敏算法，开箱即用。

---

## 内置算法全量对照表

| `MaskType` 枚举值 | 策略实现类 | 算法规则与逻辑 | 示例入参 | 脱敏输出 |
| :--- | :--- | :--- | :--- | :--- |
| `NAME` | `NameMaskPolicy` | **中英文智能识别**：<br/>• 中文 $\le 3$ 字符：保留末尾 1 字符 (`mask(val, 0, 1)`)<br/>• 中文 $> 3$ 字符：保留末尾 2 字符 (`mask(val, 0, 2)`)<br/>• 英文/非汉字：保留首 1 尾 1 字符 (`mask(val, 1, 1)`) | `"张三"`<br/>`"周杰伦"`<br/>`"诸葛孔明"`<br/>`"Steve Jobs"` | `"*三"`<br/>`"**伦"`<br/>`"**孔明"`<br/>`"S********s"` |
| `MOBILE` | `MobileMaskPolicy` | 保留前 3 个字符与后 3 个字符 (`mask(val, 3, 3)`) | `"13812345678"` | `"138*****678"` |
| `BANK_CARD_NO` | `BankCardNoMaskPolicy` | 保留前 4 个字符与后 2 个字符 (`mask(val, 4, 2)`) | `"6222020212345678"` | `"6222**********78"` |
| `ID_CARD_NO` | `IdCardNoMaskPolicy` | 保留前 5 个字符与后 2 个字符 (`mask(val, 5, 2)`) | `"440111199001011234"` | `"44011***********34"` |
| `EMAIL` | `EmailMaskPolicy` | `@` 前缀 $\le 1$ 字符转为 `*@domain`；多字符前缀保留首字符并拼接 `****@domain` | `"a@qq.com"`<br/>`"jay.chou@gmail.com"` | `"*@qq.com"`<br/>`"j****@gmail.com"` |
| `ADDRESS` | `AddressMaskPolicy` | 保留前 9 个字符，后续字符全部替换为 `*` (`mask(val, 9, 0)`) | `"北京市海淀区中关村南大街1号院"` | `"北京市海淀区中关村******"` |
| `PASSWORD` | `PasswordMaskPolicy` | 固定返回 6 个星号 (`"******"`) | `"MyPassword123"` | `"******"` |
| `B1A1` | `B1A1MaskPolicy` | 仅保留首 1 字符与尾 1 字符 (`mask(val, 1, 1)`) | `"ABCD"` | `"A**D"` |
| `B2A2` | `B2A2MaskPolicy` | 仅保留首 2 字符与尾 2 字符 (`mask(val, 2, 2)`) | `"ABCDEF"` | `"AB**EF"` |
| `PERCENT66` | `Percent66MaskPolicy` | 居中掩码总长度的约 66% 字符 (`ceil(len * 0.66)`) | `"123456789"` | `"1******89"` |
| `PERCENT66_LIMIT10` | `Percent66Limit10MaskPolicy` | 居中掩码 66% 字符，且输出截断限制最多 10 个字符 | 超长字符串 | 限制最多 10 字符 |
| `PERCENT1_LIMIT200` | `Percent1Limit200MaskPolicy` | 居中掩码 1% 字符，且输出截断限制最多 200 个字符 | 超长报文 (如大报文) | 限制最多 200 字符 |
| `HIDE` | `HideMaskPolicy` | 全部隐藏，固定返回单个星号 (`"*"`) | `"SecretData"` | `"*"` |
| `NULL` | `NullMaskPolicy` | 强制返回 `null`（常用于敏感凭据字段） | `"SecretKey"` | `null` |
| `NONE` | `NoneMaskPolicy` | 不进行脱敏，原样返回明文字符串 | `"PublicData"` | `"PublicData"` |

---

## 算法实现细节与边界行为

### `NameMaskPolicy` 汉字识别与处理
通过 Unicode Script 判定字符串中是否包含汉字：
```java
if (value.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
    // 包含汉字：按中文规则处理
    int length = MaskUtils.codePointLength(value);
    if (length <= 3) {
        return MaskUtils.mask(value, 0, 1); // 1~3个字：隐藏姓氏，保留尾字
    } else {
        return MaskUtils.mask(value, 0, 2); // 4个字及以上（复姓/少数民族）：保留后2字
    }
}
// 英文或纯拼音姓名：保留首尾字母
return MaskUtils.mask(value, 1, 1);
```

### `MaskUtils.maskByPercent` 居中掩码算法
- 计算掩码星号数量：`maskLength = (int) Math.ceil(length * (percent / 100.0))`；
- 计算居中起始索引：`start = (length - maskLength) / 2`；
- 保留前缀 `[0, start)` + 填充 `maskLength` 个 `*` + 保留后缀 `[start + maskLength, length)`。

### 空值与边界安全
- **入参为 `null` 或空字符串 `""`**：直接原样返回，不抛出任何异常；
- **保留前缀加后缀超过字符串总长度**（如 3 字符字符串执行 `mask(val, 2, 2)`）：`MaskUtils.mask` 安全判断 `prefix + suffix >= length`，直接返回原字符串，防止产生负数星号循环或越界；
- **未知脱敏 Key**：`FastMasker.mask(val, "UNKNOWN_KEY")` 抛出 `IllegalArgumentException`，消息为 `Unknown mask policy: UNKNOWN_KEY`；null、空串和空白策略同样拒绝。只有显式 `NONE` 返回原文。
