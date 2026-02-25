package com.team4u.framework.criterion.util;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ObjectUtil;

/**
 * 对象比较工具类
 *
 * @author jay.wu
 */
public class ObjectCompareUtil {

    /**
     * 宽松的等值比较
     * <p>
     * 1. 如果都是 Number，使用 FastNumberUtil 进行数值精准对齐比较
     * 2. 否则使用 ObjectUtil.equal 进行常规比较
     *
     * @param obj1 对象1
     * @param obj2 对象2
     * @return 是否相等
     */
    public static boolean looseEquals(Object obj1, Object obj2) {
        if (obj1 == obj2) {
            return true;
        }
        if (obj1 == null || obj2 == null) {
            return false;
        }
        if (obj1 instanceof Number && obj2 instanceof Number) {
            return FastNumberUtil.compare((Number) obj1, (Number) obj2) == 0;
        }
        if (ObjectUtil.equal(obj1, obj2)) {
            return true;
        }

        // 兜底：宽容模式下，最后尝试转为字符串进行字面值比较（例如 Integer(1) 与 "1"）
        String s1 = Convert.toStr(obj1);
        String s2 = Convert.toStr(obj2);
        return ObjectUtil.equal(s1, s2);
    }
}
