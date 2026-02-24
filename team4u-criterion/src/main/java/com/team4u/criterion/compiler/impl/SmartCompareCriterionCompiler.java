package com.team4u.criterion.compiler.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import com.team4u.criterion.MatchPredicate;
import com.team4u.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.criterion.model.CompareOperators;
import com.team4u.criterion.model.CriterionVisitor;
import com.team4u.criterion.model.SmartCompareCriterion;
import com.team4u.criterion.model.value.FixedValue;
import com.team4u.criterion.model.value.Value;
import com.team4u.criterion.util.FastNumberUtil;
import com.team4u.criterion.util.ObjectCompareUtil;

import java.util.function.IntPredicate;

/**
 * 智能比较规则编译器
 */
public class SmartCompareCriterionCompiler extends AbstractCriterionCompiler<SmartCompareCriterion> {
    private final Log log = Log.get();

    @Override
    public MatchPredicate compile(SmartCompareCriterion criterion, CriterionVisitor<MatchPredicate> visitor) {
        IntPredicate logic = CompareOperators.get(criterion.getOperator());
        if (logic == null) {
            throw new UnsupportedOperationException("不支持的关系操作符: " + criterion.getOperator());
        }

        Value<?> valueProvider = criterion.getValueProvider();

        // 静态优化分支：如果预期值是常量，我们可以提前窥探它的类型
        if (valueProvider instanceof FixedValue) {
            Object expected = valueProvider.get(null);

            if (expected == null) {
                return context -> handleNullCompare(context.getActual(), null, criterion.getOperator());
            }

            // 预期值尝试解析数字
            Number expectedNum = FastNumberUtil.toNumber(expected);
            if (expectedNum != null) {
                // 编译期确定：如果是整数，生成纯 long 比较闭包
                if (!FastNumberUtil.isFloatingPoint(expectedNum)) {
                    long constantLong = expectedNum.longValue();
                    return buildStaticLongPredicate(constantLong, logic);
                }
                // 编译期确定：如果是浮点数，生成纯 double 比较闭包
                else {
                    double constantDouble = expectedNum.doubleValue();
                    return buildStaticDoublePredicate(constantDouble, logic);
                }
            }

            // 预期值是明确的字符串常量 -> 退化为高效的 String 比较逻辑
            if (expected instanceof String) {
                return buildStaticStringPredicate((String) expected, criterion.getOperator());
            }
        }

        // 动态推断分支：预期值是变量，延迟到运行时推断
        return safe(context -> {
            Object actual = context.getActual();
            Object expected = valueProvider.get(context);

            if (actual == null || expected == null) {
                return handleNullCompare(actual, expected, criterion.getOperator());
            }

            // 运行时智能比较 - 如果是严格模式，开启严格类型对齐检测
            int compareResult = smartCompare(context.isStrictMode(), actual, expected);
            // 类型不匹配且非严格模式时，smartCompare 返回 Integer.MIN_VALUE 表示不匹配
            if (compareResult == Integer.MIN_VALUE) {
                return false;
            }
            return logic.test(compareResult);
        });
    }

    /**
     * 智能比较核心逻辑
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private int smartCompare(boolean isStrictMode, Object actual, Object expected) {
        // 快速判断：完全相同的对象
        if (actual == expected || actual.equals(expected)) {
            return 0;
        }

        // 数字优先策略：尝试提取为数字
        if (actual instanceof Number || expected instanceof Number) {
            Number actualNum = FastNumberUtil.toNumber(actual);
            Number expectedNum = FastNumberUtil.toNumber(expected);
            if (actualNum == null) {
                throw new NumberFormatException("无效的数字格式: " + actual);
            }
            if (expectedNum == null) {
                throw new NumberFormatException("无效的数字格式: " + expected);
            }
            return FastNumberUtil.compare(actualNum, expectedNum);
        }

        // 同类型 Comparable 策略 (例如两个 Date，或两个实现了 Comparable 的自定义对象，支持继承)
        if (actual instanceof Comparable && expected instanceof Comparable
                && (actual.getClass().isAssignableFrom(expected.getClass())
                        || expected.getClass().isAssignableFrom(actual.getClass()))) {
            return ((Comparable) actual).compareTo(expected);
        }

        // 兜底策略：字符串字典序比较
        // 遇到类型不匹配且无法使用上方的内置转换比较时，不再静默降级为字符串比较
        if (!(actual instanceof String && expected instanceof String)) {
            String errorMsg = String.format(
                    "Type mismatch and cannot be implicitly converted: actualType=%s, expectedType=%s",
                    actual.getClass().getSimpleName(),
                    expected.getClass().getSimpleName());
            if (isStrictMode) {
                throw new IllegalArgumentException(errorMsg);
            }

            log.error("SmartCompareCriterionCompiler|smartCompare|fail|msg={}|actual={}|expected={}",
                    errorMsg, actual, expected);
            return Integer.MIN_VALUE;
        }

        String actualStr = Convert.toStr(actual, "");
        String expectedStr = Convert.toStr(expected, "");
        return actualStr.compareTo(expectedStr);
    }

    private boolean handleNullCompare(Object actual, Object expected, String operator) {
        boolean bothNull = (actual == null && expected == null);
        if ("==".equals(operator) || "=".equals(operator))
            return bothNull;
        if ("!=".equals(operator))
            return !bothNull;
        // 对于大于、小于等运算，遇到 null 时统一作为不匹配
        return false;
    }

    /**
     * 编译静态长整体验证分支（性能优化：支持长整数原生比较，0 内存分配）
     */
    private MatchPredicate buildStaticLongPredicate(long constantLong, IntPredicate logic) {
        return safe(context -> {
            Object actualObj = context.getActual();
            if (actualObj == null) {
                return false;
            }
            Number actualNum = FastNumberUtil.toNumber(actualObj);
            if (actualNum == null) {
                throw new NumberFormatException("无效的数字格式: " + actualObj);
            }
            // 如果运行时输入也是整数，走极限极速通道 (0 对象创建)
            if (!FastNumberUtil.isFloatingPoint(actualNum)) {
                return logic.test(Long.compare(actualNum.longValue(), constantLong));
            }
            // 遇到输入是浮点数，降级为 Double 比较
            return logic.test(Double.compare(actualNum.doubleValue(), (double) constantLong));
        });
    }

    /**
     * 编译静态浮点数验证分支
     */
    private MatchPredicate buildStaticDoublePredicate(double constantDouble, IntPredicate logic) {
        return safe(context -> {
            Object actualObj = context.getActual();
            if (actualObj == null) {
                return false;
            }
            Number actualNum = FastNumberUtil.toNumber(actualObj);
            if (actualNum == null) {
                throw new NumberFormatException("无效的数字格式: " + actualObj);
            }
            return logic.test(Double.compare(actualNum.doubleValue(), constantDouble));
        });
    }

    /**
     * 编译静态字符串验证分支
     */
    private MatchPredicate buildStaticStringPredicate(String expected, String operator) {
        return safe(context -> {
            Object actualObj = context.getActual();
            String actual = actualObj != null ? actualObj.toString() : null;

            switch (operator) {
                case "==":
                case "=":
                    return ObjectCompareUtil.looseEquals(actualObj, expected);
                case "!=":
                    return !ObjectCompareUtil.looseEquals(actualObj, expected);
                case "contains":
                    return StrUtil.contains(actual, expected);
                default:
                    // 针对字符串的比较运算，如果不是等于或者不等于，则采用默认字典序回调
                    if (actual == null) {
                        return false;
                    }
                    IntPredicate runtimeLogic = CompareOperators.get(operator);
                    return runtimeLogic != null && runtimeLogic.test(actual.compareTo(expected));
            }
        });
    }
}
