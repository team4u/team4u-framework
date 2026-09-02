# 快速开始

本章节演示如何使用 `team4u-parser` 提供的核心游标与定位工具快速构建词法扫描器与语法解析器。

---

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-parser</artifactId>
</dependency>
```

---

## 构建词法扫描器

利用 [`CharCursor`](file:///root/code/team4u-framework/modules/parser/core/src/main/java/com/team4u/framework/parser/CharCursor.java) 进行字符流扫描，可自动跟踪行号与列号，并通过 `mark()` 与 `spanFrom(mark)` 直接生成精确的源码区间：

```java
import com.team4u.framework.parser.CharCursor;
import com.team4u.framework.parser.SourceSpan;

public class SimpleLexer {

    public static void scan(String text, String sourceName) {
        CharCursor cursor = new CharCursor(text, sourceName);

        while (cursor.hasNext()) {
            char c = cursor.peek();

            // 跳过空白字符
            if (Character.isWhitespace(c)) {
                cursor.advance();
                continue;
            }

            // 记录当前记号起始位置
            CharCursor.Mark mark = cursor.mark();

            if (Character.isLetter(c)) {
                // 读取标识符
                while (cursor.hasNext() && Character.isLetterOrDigit(cursor.peek())) {
                    cursor.advance();
                }
                SourceSpan span = cursor.spanFrom(mark);
                String identifier = text.substring(mark.offset(), cursor.offset());
                System.out.println("Identifier: " + identifier + " at " + span.format());
            } else if (Character.isDigit(c)) {
                // 读取数字
                while (cursor.hasNext() && Character.isDigit(cursor.peek())) {
                    cursor.advance();
                }
                SourceSpan span = cursor.spanFrom(mark);
                String number = text.substring(mark.offset(), cursor.offset());
                System.out.println("Number: " + number + " at " + span.format());
            } else {
                cursor.advance();
                SourceSpan span = cursor.spanFrom(mark);
                System.out.println("Symbol: " + c + " at " + span.format());
            }
        }
    }
}
```

---

## 构建语法解析器与投机回滚

在编写递归下降解析器时，可使用 [`TokenCursor`](file:///root/code/team4u-framework/modules/parser/core/src/main/java/com/team4u/framework/parser/TokenCursor.java) 处理多分支预测与投机回滚：

```java
import com.team4u.framework.parser.TokenCursor;
import java.util.List;

public class SimpleParser {

    private final TokenCursor<String> cursor;

    public SimpleParser(List<String> tokens) {
        this.cursor = new TokenCursor<>(tokens);
    }

    public void parseExpression() {
        // 尝试解析分支 A
        int mark = cursor.mark();
        if (tryParseBranchA()) {
            return;
        }

        // 尝试失败，回滚游标并尝试分支 B
        cursor.reset(mark);
        if (tryParseBranchB()) {
            return;
        }

        // 均不匹配，抛出解析异常
        cursor.reset(mark);
        throw new IllegalStateException("Unexpected token: " + cursor.peek());
    }

    private boolean tryParseBranchA() {
        if ("let".equals(cursor.peek())) {
            cursor.advance();
            // ... 进一步解析
            return true;
        }
        return false;
    }

    private boolean tryParseBranchB() {
        if ("const".equals(cursor.peek())) {
            cursor.advance();
            // ... 进一步解析
            return true;
        }
        return false;
    }
}
```

---

## 源码区间定位与异常报告

[`SourceSpan`](file:///root/code/team4u-framework/modules/parser/core/src/main/java/com/team4u/framework/parser/SourceSpan.java) 提供了统一的区间合并与格式化能力，方便在上层抛出结构化错误信息：

```java
SourceSpan span1 = SourceSpan.range(0, 5, 1, 1, 1, 6, "example.dsl");
SourceSpan span2 = SourceSpan.range(10, 15, 1, 11, 1, 16, "example.dsl");

// 合并两个子表达式区间
SourceSpan fullSpan = span1.merge(span2);

// 输出格式化坐标：example.dsl:1:1..1:16
System.out.println(fullSpan.format());
```
