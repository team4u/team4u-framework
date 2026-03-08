package com.team4u.framework.retry.recovery;

import cn.hutool.core.convert.Convert;
import cn.hutool.json.JSONUtil;
import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.retry.backend.RetryTaskSnapshot;
import com.team4u.framework.retry.backend.serialize.HutoolRetryTaskSnapshotSerializer;
import com.team4u.framework.retry.backend.serialize.RetryTaskSnapshotSerializer;
import lombok.Setter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 基于重试任务快照的通用恢复处理器
 * <p>
 * 该处理器通过反序列化持久化存储的任务快照，提取出原始方法调用的 Bean 名称、方法名以及参数值。
 * 随后结合 {@link BeanManager} 定位目标对象，通过反射重新触发业务逻辑。
 * 它主要用于应用重启或宕机恢复后的持久化重试任务处理。
 */
public class SnapshotRecoveryHandler implements RecoveryHandler {

    /**
     * 该处理器的唯一标识 Key，通常对应某种任务类型
     */
    private final String key;

    /**
     * 快照序列化器，用于将持久化存储的字符串转换为任务快照对象
     */
    @Setter
    private RetryTaskSnapshotSerializer snapshotSerializer = HutoolRetryTaskSnapshotSerializer.INSTANCE;

    /**
     * 构造快照恢复处理器
     *
     * @param key 处理器标识 Key
     */
    public SnapshotRecoveryHandler(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public void recover(RetryTaskSnapshot snapshot) throws Exception {
        if (snapshot == null) {
            throw new IllegalArgumentException("任务快照不能为空");
        }

        // 解析目标 Bean、方法及其参数
        Object bean = resolveBean(snapshot.getBeanName());
        Method method = resolveMethod(bean.getClass(), snapshot.getMethodName(), snapshot.getArgTypes());
        Object[] args = deserializeArgs(snapshot.getArgTypes(), snapshot.getArgJsonValues());

        // 在恢复上下文中执行目标方法。
        // RecoveryExecutionContext 会标记当前处于恢复状态，RetryDelegate 识别到该状态后
        // 会跳过重试增强逻辑，防止在恢复过程中再次触发循环重试。
        RecoveryExecutionContext.run((RecoveryExecutionContext.CheckedRunnable) () -> invoke(bean, method, args));
    }

    /**
     * 根据 Bean 名称解析目标对象
     * <p>
     * 优先从 BeanManager 中按名称查找，若未找到，则尝试将名称视为全限定类名，从 BeanManager 中按类型查找。
     *
     * @param beanName Bean 名称或全限定类名
     * @return 目标 Bean 实例
     */
    protected Object resolveBean(String beanName) {
        if (beanName == null || beanName.trim().isEmpty()) {
            throw new IllegalArgumentException("快照中 Bean 名称不能为空");
        }

        BeanManager beanManager = BeanManager.getInstance();
        Object bean = beanManager.getBean(beanName);
        if (bean != null) {
            return bean;
        }

        // 尝试作为类名进行解析
        try {
            Class<?> beanType = Class.forName(beanName);
            bean = beanManager.getBean(beanType);
            if (bean != null) {
                return bean;
            }
        } catch (ClassNotFoundException ignored) {
        }

        throw new IllegalStateException("无法通过 BeanManager 解析目标对象，名称: " + beanName);
    }

    /**
     * 根据方法名及参数类型解析目标方法对象
     *
     * @param beanClass    目标对象类
     * @param methodName   方法名称
     * @param argTypeNames 参数类型名称列表
     * @return 反射 Method 对象
     */
    protected Method resolveMethod(Class<?> beanClass, String methodName, List<String> argTypeNames) {
        Class<?>[] argTypes = toClasses(argTypeNames);
        try {
            Method method = beanClass.getMethod(methodName, argTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "无法解析恢复方法。类: " + beanClass.getName()
                            + ", 方法: " + methodName
                            + ", 参数类型: " + argTypeNames,
                    e);
        }
    }

    /**
     * 反序列化方法调用参数列表
     *
     * @param argTypeNames  参数类型全称列表
     * @param argJsonValues 对应的参数 JSON 字符串列表
     * @return 反序列化后的参数对象数组
     */
    protected Object[] deserializeArgs(List<String> argTypeNames, List<String> argJsonValues) {
        if (argTypeNames == null || argTypeNames.isEmpty()) {
            return new Object[0];
        }
        if (argJsonValues == null || argJsonValues.size() != argTypeNames.size()) {
            throw new IllegalArgumentException(
                    "任务快照中参数值数量与参数类型数量不匹配。类型数量: "
                            + argTypeNames.size() + ", 参数值数量: "
                            + (argJsonValues == null ? null : argJsonValues.size()));
        }

        Object[] args = new Object[argTypeNames.size()];
        for (int i = 0; i < argTypeNames.size(); i++) {
            Class<?> argType = toClass(argTypeNames.get(i));
            String json = argJsonValues.get(i);
            args[i] = deserializeArg(argType, json);
        }
        return args;
    }

    /**
     * 将 JSON 字符串反序列化为指定类型的参数对象
     *
     * @param argType 目标参数类型
     * @param json    参数 JSON 字符串
     * @return 反序列化后的参数对象
     */
    protected Object deserializeArg(Class<?> argType, String json) {
        if (json == null) {
            return null;
        }
        // 处理基本类型及其包装类、字符串等简单类型
        if (isSimpleType(argType)) {
            Object value = JSONUtil.parseArray("[" + json + "]").get(0);
            if (argType == char.class || argType == Character.class) {
                String text = Convert.toStr(value);
                return text == null || text.isEmpty() ? '\0' : text.charAt(0);
            }
            return Convert.convert(argType, value);
        }
        // 处理复杂对象
        return JSONUtil.toBean(json, argType);
    }

    /**
     * 判断指定类是否为简单类型（基本类型及其包装类、字符串等）
     */
    protected boolean isSimpleType(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Character.class;
    }

    /**
     * 反射调用目标方法，处理异常转换
     */
    protected void invoke(Object bean, Method method, Object[] args) throws Exception {
        try {
            method.invoke(bean, args);
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target instanceof Exception) {
                throw (Exception) target;
            }
            if (target instanceof Error) {
                throw (Error) target;
            }
            throw new RuntimeException(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("调用恢复方法失败: " + method, e);
        }
    }

    /**
     * 将全限定类名列表转换为 Class 数组
     */
    protected Class<?>[] toClasses(List<String> typeNames) {
        Class<?>[] types = new Class<?>[typeNames == null ? 0 : typeNames.size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = toClass(typeNames.get(i));
        }
        return types;
    }

    /**
     * 将全限定类名映射为 Class 对象，额外支持 Java 8 种基本类型的映射
     */
    protected Class<?> toClass(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("参数类型名称不能为空");
        }

        switch (name) {
            case "boolean":
                return boolean.class;
            case "byte":
                return byte.class;
            case "short":
                return short.class;
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "char":
                return char.class;
            case "void":
                return void.class;
        }

        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("无法从上下文加载参数类型: " + name, e);
        }
    }
}