package com.team4u.criterion.model;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * 比较操作符工具类
 * <p>
 * 提供通用的比较逻辑映射，消除不同规则实现中的重复代码。
 *
 * @author jay.wu
 */
public class CompareOperators {

    private static final Map<String, IntPredicate> OPERATORS = new HashMap<>();

    static {
        // 比较结果: 1 (实际值 > 预期值), 0 (实际值 == 预期值), -1 (实际值 < 预期值)
        OPERATORS.put(">", c -> c > 0);
        OPERATORS.put(">=", c -> c >= 0);
        OPERATORS.put("<", c -> c < 0);
        OPERATORS.put("<=", c -> c <= 0);
        OPERATORS.put("=", c -> c == 0);
        OPERATORS.put("==", c -> c == 0);
        OPERATORS.put("!=", c -> c != 0);
    }

    /**
     * 获取指定操作符的比较逻辑
     *
     * @param operator 操作符 (如 ">", "<=", "==")
     * @return 比较逻辑谓词，若操作符不支持则返回 null
     */
    public static IntPredicate get(String operator) {
        return OPERATORS.get(operator);
    }
}
