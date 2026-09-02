# 核心机制与游标设计

`team4u-parser` 提供了三大核心基础抽象：源码范围定位模型 [`SourceSpan`](file:///root/code/team4u-framework/modules/parser/core/src/main/java/com/team4u/framework/parser/SourceSpan.java)、字符流游标 [`CharCursor`](file:///root/code/team4u-framework/modules/parser/core/src/main/java/com/team4u/framework/parser/CharCursor.java) 与记号流游标 [`TokenCursor`](file:///root/code/team4u-framework/modules/parser/core/src/main/java/com/team4u/framework/parser/TokenCursor.java)。

---

## 源码范围定位模型

[`SourceSpan`](file:///root/code/team4u-framework/modules/parser/core/src/main/java/com/team4u/framework/parser/SourceSpan.java) 是一个完全不可变的数据模型，用于精确记录语法节点或词法单元在源码中的物理位置。

### 坐标与区间语义

- **半开区间** ：所有字符偏移量均基于 `[startOffset, endOffset)` 表达。例如长度为 3 的字符串，区间为 `0..3`；
- **点位与零宽度支持** ：允许 `startOffset == endOffset`，用于表示插入点或空输入位置；
- **行列坐标** ：`startLine`、`startColumn`、`endLine`、`endColumn` 均以 1 为起始索引；
- **来源标识** ：支持附带 `source`（如文件名或 URI），方便跨文件诊断定位；
- **未知哨兵** ：通过 `SourceSpan.UNKNOWN` 表示未定义或内置生成的虚拟节点。

### 核心方法与格式化

```java
// 创建单点位置
SourceSpan point = SourceSpan.point(0, 1, 1, "test.dsl");

// 创建范围位置
SourceSpan range = SourceSpan.range(0, 5, 1, 1, 1, 6, "test.dsl");

// 格式化输出：单点为 "test.dsl:1:1"，范围为 "test.dsl:1:1..1:6"
String formatted = range.format();

// 合并范围（自动选取最小起始点与最大结束点）
SourceSpan merged = range.merge(anotherSpan);
```

---

## 字符流游标

[`CharCursor`](file:///root/code/team4u-framework/modules/parser/core/src/main/java/com/team4u/framework/parser/CharCursor.java) 专为词法分析设计，负责将字符序列转化为具有行列上下文的有序滑动窗口。

### 自动识别与兼容三种换行符

在不同操作系统与源码格式下，换行符存在 `\n` (Unix)、`\r\n` (Windows) 和 `\r` (Classic Mac) 三种形式。`CharCursor` 在滑动时自动识别多字符换行，并确保：
- 行号 `line` 自增 1；
- 列号 `column` 准确重置为 1；
- 字符偏移量 `offset` 按实际物理字节步进。

### 高效前瞻与区间标记

- **$O(1)$ 前瞻** ：`peek()` 获取当前字符，`peek(n)` 窥视前方第 n 个字符；
- **安全边界** ：越界时返回 `\0`，通过 `hasNext()` 与 `has(n)` 判定余量；
- **标记与切片** ：`mark()` 返回当前瞬时标记，解析完成后通过 `spanFrom(mark)` 零拷贝计算出精确的 `SourceSpan`。

```java
CharCursor cursor = new CharCursor("abc\r\ndef", "sample.txt");

CharCursor.Mark mark = cursor.mark();
cursor.advance(); // 'a'
cursor.advance(); // 'b'
cursor.advance(); // 'c'
cursor.advance(); // '\r'
cursor.advance(); // '\n'

// 得到跨越换行的精确 SourceSpan
SourceSpan span = cursor.spanFrom(mark);
```

---

## 记号流游标

[`TokenCursor<T>`](file:///root/code/team4u-framework/modules/parser/core/src/main/java/com/team4u/framework/parser/TokenCursor.java) 专为语法分析设计，管理词法分析输出的记号列表。

### 泛型抽象与语言无关

`TokenCursor<T>` 不绑定任何特定的 Token 类，可容纳任意领域定义的 Token 对象：

```java
TokenCursor<MyToken> cursor = new TokenCursor<>(tokenList);
```

### 递归下降与零开销试探回滚

在语法分析中，处理多分支文法（如 LL(k) 或选择性分支）时常需要试探解析后回滚。`TokenCursor` 提供了低开销的状态记录与回滚能力：

```java
// 记录当前游标标记
int mark = cursor.mark();

try {
    // 尝试按照分支语法 A 解析
    return parseBranchA(cursor);
} catch (ParseException ex) {
    // 试探失败，回滚游标至先前标记点
    cursor.reset(mark);
    // 尝试按照分支语法 B 解析
    return parseBranchB(cursor);
}
```

- **`mark()`** ：直接捕获当前索引，开销极低；
- **`reset(mark)`** ：将游标重置到指定标记，实现快速回滚；
- **`previous()` / `lookbehind()`** ：支持安全回看前置已消费的记号。
