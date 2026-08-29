package com.team4u.framework.ratelimiter.core;

import com.team4u.framework.base.util.ReflectUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

/**
 * 检查上下文的属性取值工具（限流模块内部公共设施）
 * <p>
 * 此前 {@link RateLimitEngine} 的键模板变量解析（{@code resolveVariable}）与
 * {@link HistoryPaths} 的单段访问（{@code access}）各自持有一份同构的
 * 「Map 按键取值 / List 按下标取值 / Bean 反射读公有 getter」逻辑，收敛于此。
 * 任一环节缺失（键不存在、下标越界、无 getter）返回 null，不做任何抛错。
 * </p>
 *
 * @author jay.wu
 */
final class ContextProperties {

    private ContextProperties() {
    }

    /**
     * 按名字从上下文取值：Map 按键取值，其余按公有 getter 反射读取
     * <p>
     * getter 查找顺序为 {@code getXxx} 与 {@code isXxx}（无参、非 void、公有），
     * 与既有键模板渲染语义保持一致。
     *
     * @param context 检查上下文（Map 或任意 Bean）
     * @param name    属性名
     * @return 属性值；context 为 null、名字为空、无 getter 或读取失败返回 null
     */
    static Object get(Object context, String name) {
        if (context == null || name == null || name.isEmpty()) {
            return null;
        }
        if (context instanceof Map) {
            return ((Map<?, ?>) context).get(name);
        }
        Method getter = findGetter(context.getClass(), name);
        if (getter == null) {
            return null;
        }
        return ReflectUtil.invoke(context, getter);
    }

    /**
     * 单段访问：Map 取键 / List 按数字下标取值 / Bean 读公有 getter
     * <p>
     * List 段非数字或下标越界返回 null（history-window 点路径导航语义）。
     *
     * @param target  当前导航到的对象
     * @param segment 路径段（属性名或下标）
     * @return 段对应的值；无法解析返回 null
     */
    static Object access(Object target, String segment) {
        if (target instanceof Map) {
            return ((Map<?, ?>) target).get(segment);
        }
        if (target instanceof List) {
            int index;
            try {
                index = Integer.parseInt(segment);
            } catch (NumberFormatException e) {
                return null;
            }
            List<?> list = (List<?>) target;
            return index >= 0 && index < list.size() ? list.get(index) : null;
        }
        Method getter = findGetter(target.getClass(), segment);
        if (getter == null) {
            return null;
        }
        return ReflectUtil.invoke(target, getter);
    }

    /**
     * 查找公有无参非 void 的 getXxx/isXxx getter；未找到返回 null
     */
    private static Method findGetter(Class<?> type, String name) {
        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String prefix : new String[]{"get", "is"}) {
            Method getter = ReflectUtil.getMethod(type, prefix + capitalized);
            if (getter != null && Modifier.isPublic(getter.getModifiers())
                    && getter.getParameterCount() == 0 && getter.getReturnType() != void.class) {
                return getter;
            }
        }
        return null;
    }
}
