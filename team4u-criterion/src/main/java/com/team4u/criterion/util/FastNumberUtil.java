package com.team4u.criterion.util;

import cn.hutool.core.util.NumberUtil;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 极速数字处理工具
 * <p>
 * 专为开关路由等高并发场景设计，避免 BigDecimal 的内存分配 and 计算开销。
 * </p>
 *
 * @author jay.wu
 */
public class FastNumberUtil {

    private FastNumberUtil() {
    }

    /**
     * 将未知对象快速转换为 Number
     * 尽量避免 BigDecimal，优先返回 Long，降级返回 Double
     */
    public static Number toNumber(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Number) {
            return (Number) obj;
        }
        if (obj instanceof String) {
            String str = (String) obj;
            if (str.isEmpty()) {
                return null;
            }

            if (!NumberUtil.isNumber(str)) {
                return null;
            }

            try {
                // 如果包含小数点或科学计数法符号，直接解析为 Double
                if (str.indexOf('.') != -1 || str.indexOf('e') != -1 || str.indexOf('E') != -1) {
                    return Double.parseDouble(str);
                }

                // 如果长度超过 19（Long.MAX_VALUE 长度），可能是超出 Long 范围的数字，直接走 Double
                // 避免 Long.parseLong 抛出异常的开销
                if (str.length() > 19) {
                    return Double.parseDouble(str);
                }

                // 优先尝试解析为 Long (涵盖大多数 ID 或整形标识)
                return Long.parseLong(str);
            } catch (NumberFormatException e) {
                return null; // 不是合法数字
            }
        }
        return null;
    }

    /**
     * 极速数字比较（无 BigDecimal，无 GC 开销）
     */
    public static int compare(Number n1, Number n2) {
        if (Objects.equals(n1, n2)) {
            return 0;
        }
        // 只要有一个是浮点数，就按 Double 比较
        if (isFloatingPoint(n1) || isFloatingPoint(n2)) {
            return Double.compare(n1.doubleValue(), n2.doubleValue());
        }
        // 否则全部按 Long 比较
        return Long.compare(n1.longValue(), n2.longValue());
    }

    /**
     * 判断是否为浮点数类型
     */
    public static boolean isFloatingPoint(Number n) {
        return n instanceof Double || n instanceof Float || n instanceof BigDecimal;
    }
}