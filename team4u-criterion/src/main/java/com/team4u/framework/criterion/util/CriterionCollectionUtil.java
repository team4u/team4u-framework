package com.team4u.framework.criterion.util;

import com.team4u.framework.base.util.ConvertUtil;

import java.util.*;

/**
 * Criterion 集合转换工具类
 *
 * @author jay.wu
 */
public class CriterionCollectionUtil {

    /**
     * 将对象安全地转换为 Collection
     * <p>
     * 支持：
     * 1. Collection: 直接返回
     * 2. Array: 转换为 List
     * 3. Iterator: 转换为 List
     * 4. Iterable: 转换为 List
     * 5. 其他: 包装为单元素 List
     *
     * @param obj 待转换的对象
     * @return 转换后的 Collection，如果输入为 null 则返回 null
     */
    public static Collection<?> toCollection(Object obj) {
        if (obj == null) {
            return null;
        }

        if (obj instanceof Collection) {
            return (Collection<?>) obj;
        }

        if (obj.getClass().isArray()) {
            if (obj instanceof Object[]) {
                return Arrays.asList((Object[]) obj);
            }
            return ConvertUtil.toList(obj);
        }

        if (obj instanceof Iterator) {
            List<Object> list = new ArrayList<>();
            Iterator<?> it = (Iterator<?>) obj;
            while (it.hasNext()) {
                list.add(it.next());
            }
            return list;
        }

        if (obj instanceof Iterable) {
            List<Object> list = new ArrayList<>();
            for (Object item : (Iterable<?>) obj) {
                list.add(item);
            }
            return list;
        }

        return Collections.singletonList(obj);
    }
}
