# DSL 语法指南

`team4u-criterion` 提供了无限接近自然语言与 SQL 的强大 DSL 语法。本章详细介绍全量语法规则与边界特性。

---

## 基本词法规则

- **Subject（主语/属性）**：无 `$` 前缀（如 `age`、`user.name`），表示从 `MatchContext.getActual()` 入参对象中提取的属性。
- **Variable（动态变量）**：强制以 `$` 开头（如 `$minAge`、`$whiteList`），表示从 `MatchContext.getAttribute(key)` 中提取的变量。
- **Literal（字面量）**：
  - 数字与布尔：`18`, `3.14`, `true`, `false`, `null`
  - 带引号字符串：`'admin'`, `'VIP'`（支持转义字符 `\'`）
  - 无引号普通标识符：`ACTIVE`, `VIP`（引擎会自动解析识别）
- **关键字 `it`**：代表整个入参对象本身（例如入参为一个 Integer 或 String 时，使用 `it > 18` 或 `it =~ '^vip_.*'`）。

---

## 关系比较操作符

| 操作符 | 说明 | 示例 |
| :--- | :--- | :--- |
| `==` 或 `=` | 等于 | `role == 'ADMIN'`, `status = ACTIVE` |
| `!=` | 不等于 | `status != 'DELETED'` |
| `>` | 大于 | `age > 18` |
| `>=` | 大于等于 | `score >= 60` |
| `<` | 小于 | `price < 100` |
| `<=` | 小于等于 | `count <= 5` |

> [!NOTE]
> **智能宽容比较**：引擎底层自动屏蔽基本数值类型的差异。无论实际传入的是 `Integer`, `Long`, `Double` 还是字符串 `"100"`，`100 == 100.0` 均能精准匹配，避免反序列化类型不一致导致的异常。

---

## 逻辑组合运算

支持 `&&` (与)、`||` (或) 以及 `()` 括号调整优先级，遵循短路求值原则：

```sql
age >= 18 && (userLevel in ['VIP', 'SVIP'] || totalAmount > 5000)
```

---

## 空值与存在性检查 (Is / Is Not)

| 语法 | 说明 | 示例 |
| :--- | :--- | :--- |
| `is null` | 对象为 null | `avatar is null` |
| `is not null` | 对象非 null | `email is not null` |
| `is empty` | 对象为 null、空字符串或空集合/Map/数组 | `tags is empty` |
| `is not empty` | 对象非空且有内容 | `name is not empty` |

---

## 集合与容器操作

### In / Not In (成员判定)
- 常量集合：`status in ['PAID', 'SUCCESS']` 或 `status in [PAID, SUCCESS]`
- 排除集合：`id not in [1, 2, 3]`
- 混合变量：`id in [1, 2, $specialId]`
- 动态集合引用：`userRole in $allowedRoles`

### Contains / ContainsAny / ContainsAll (容器包含)
- **`contains`**：判断集合是否包含指定元素，或字符串是否包含子串
  - `roles contains 'ADMIN'` (roles 为 List/Set)
  - `description contains 'error'` (字符串包含)
- **`containsAny` / `contains_any` / `contains any`**：交集检查，实际集合中是否包含预期集合中的任一元素
  - `tags containsAny ['VIP', 'KOL']`
  - `roles contains any ['ADMIN', 'MANAGER']`
- **`containsAll` / `contains_all` / `contains all`**：全集包含，实际集合是否完全包含预期集合的所有元素
  - `userTags containsAll ['NEW_USER', 'PHONE_VERIFIED']`
  - `permissions contains all ['READ', 'WRITE']`

---

## 区间范围 (Between)

支持标准数学区间语法，`[` / `]` 表示闭区间（包含边界），`(` / `)` 表示开区间（不包含边界）：

- **全闭区间**：`age between [18, 60]` （$18 \le age \le 60$）
- **全开区间**：`score between (60, 100)` （$60 < score < 100$）
- **左闭右开**：`score between [60, 100)` （$60 \le score < 100$）
- **左开右闭**：`level between (1, 5]` （$1 < level \le 5$）
- **动态边界**：`price between [$minPrice, $maxPrice]`

---

## 正则匹配与通配符 (Regex / Like)

- **正则匹配 (`=~` 或 `regex`)**：
  - `email =~ '^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$'`
  - `phone regex '^1[3-9]\\d{9}$'`
- **通配符匹配 (`like`)**：基于 AntPathMatcher，支持 `*` (多个字符) 与 `?` (单个字符)
- **通配符匹配 (`like`)**：基于 Team4u Ant 风格路径语义：`*` 匹配单个 `/` 分段内多个字符，`?` 匹配单个非 `/` 字符，只有完整的 `**` 分段可跨越目录；`***` 等更长星号串仍是分段内通配符，`\` 为普通字符
  - `name like 'John*'`
  - `code like 'ERR_???'`
  - `path like '/api/v1/**'`
---

## 概率灰度与 Hash 分流

### 随机概率 (`prob` / `probability`)
按指定浮点概率随机命中（`0.0 ~ 1.0`）：
- `it prob 0.3` (30% 随机命中)
- `it probability $grayRate` (基于上下文动态概率)

### 一致性 Hash 分流 (`hash` / `hash_probability`)
基于 **MurmurHash64** 算法，保证同一入参值（如 `userId`）结果稳定幂等：
- `userId hash 0.2` (固定圈选 20% 用户)
- **盐值正交性 (`salt`)**：通过在上下文设置 `context.setAttribute("salt", "EXP_A")`，可保证不同实验之间的哈希分流彼此正交、流量均匀分散。

---

## 显式类型转换器 (ValueConverter)

通过 `subject:converter` 后缀语法在比较前执行前置类型转换：

| 转换器 | 说明 | 示例 |
| :--- | :--- | :--- |
| `:date` | 转换为日期进行比较，支持标准格式与 `'now'` 关键字 | `createTime:date > '2023-01-01'`<br/>`expireTime:date < 'now'`<br/>`birth:date between ['1990-01-01', '2000-01-01']` |
| `:version` | 语义化版本号比较（Semantic Versioning） | `appVersion:version >= '2.1.0'`<br/>`clientVer:version between ['1.0.0', '2.0.0')` |
| `:number` | 转换为数值类型（走 0 GC 极速路径） | `price:number > 100` |
| `:size` | 获取集合、数组、Map 或字符串的长度/大小 | `followers:size > 1000`<br/>`items:size >= 5`<br/>`name:size < 10` |
| `:string` | 调用 `String.valueOf(obj)` 转换为字符串 | `code:string == '1001'` |

---

## 极简语法糖

在很多规则表中，简单的等值匹配非常普遍。Criterion 提供了极简语法糖：

- **数值简写**：`18` 等价于 `it == 18`
- **字符串简写**：`'SUCCESS'` 等价于 `it == 'SUCCESS'`
- **多条件极简组合**：`18 || 20` 等价于 `it == 18 || it == 20`
