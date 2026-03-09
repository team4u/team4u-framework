package com.team4u.framework.retry.proxy;

import cn.hutool.core.util.ReflectUtil;
import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.retry.domain.store.InvocationRecoveryData;
import com.team4u.framework.retry.proxy.serialize.HutoolRetryContextSerializer;
import com.team4u.framework.retry.proxy.serialize.RetryContextSerializer;
import com.team4u.framework.retry.recovery.RecoveryContext;
import com.team4u.framework.retry.recovery.RecoveryHandler;
import lombok.Getter;
import lombok.Setter;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 通用代理方法重试放音机
 * <p>
 * 给基于代理注解模式托管的重试任务使用，负责反射调用真实的目标组件。
 */
public class InvocationReplay implements RecoveryHandler<InvocationRecoveryData> {

    public static final String TASK_NAME = "ProxyInvocationReplay";
    private static final Map<String, Class<?>> PRIMITIVE_TYPES = primitiveTypes();

    @Getter
    @Setter
    private RetryContextSerializer serializer = HutoolRetryContextSerializer.INSTANCE;

    @Override
    public String taskName() {
        return TASK_NAME;
    }

    @Override
    public void recover(InvocationRecoveryData payload, RecoveryContext context) throws Exception {
        if (payload == null) {
            throw new IllegalArgumentException("InvocationRecoveryData is null");
        }

        // 定位目标对象
        Object target = locateTarget(payload.getBeanName());

        Class<?>[] paramTypes = resolveParamTypes(payload);
        Method method = ReflectUtil.getMethod(target.getClass(), payload.getMethodName(), paramTypes);

        if (method == null) {
            throw new NoSuchMethodException(payload.getBeanName() + "." + payload.getMethodName());
        }

        Object[] args = resolveArgs(payload, paramTypes);

        ReflectUtil.invoke(target, method, args);
    }

    /**
     * 定位目标对象。
     * <p>
     * 优先从 {@link BeanManager} 获取 Bean 实例，若未找到则尝试通过反射创建新实例。
     *
     * @param beanName Bean 名称或完整类名
     * @return 目标对象实例
     * @throws ClassNotFoundException 如果找不到对应的类
     */
    private Object locateTarget(String beanName) throws ClassNotFoundException {
        try {
            // 首先尝试从 BeanManager 获取
            Class<?> clazz = Class.forName(beanName);
            Object bean = BeanManager.getInstance().getBean(clazz);
            if (bean != null) {
                return bean;
            }
        } catch (Exception ignored) {
        }
        // 回退选项：创建新实例（要求无参构造）
        return ReflectUtil.newInstance(Class.forName(beanName));
    }

    /**
     * 解析参数类型列表。
     *
     * @param payload 恢复数据负载
     * @return 参数类型数组
     * @throws ClassNotFoundException 如果参数类型类不存在
     */
    private Class<?>[] resolveParamTypes(InvocationRecoveryData payload) throws ClassNotFoundException {
        if (payload.getArgTypes() == null || payload.getArgTypes().isEmpty()) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[payload.getArgTypes().size()];
        for (int i = 0; i < payload.getArgTypes().size(); i++) {
            types[i] = resolveType(payload.getArgTypes().get(i));
        }
        return types;
    }

    private Class<?> resolveType(String typeName) throws ClassNotFoundException {
        Class<?> primitiveType = PRIMITIVE_TYPES.get(typeName);
        if (primitiveType != null) {
            return primitiveType;
        }
        return Class.forName(typeName);
    }

    private static Map<String, Class<?>> primitiveTypes() {
        Map<String, Class<?>> primitiveTypes = new HashMap<String, Class<?>>();
        primitiveTypes.put(boolean.class.getName(), boolean.class);
        primitiveTypes.put(byte.class.getName(), byte.class);
        primitiveTypes.put(char.class.getName(), char.class);
        primitiveTypes.put(short.class.getName(), short.class);
        primitiveTypes.put(int.class.getName(), int.class);
        primitiveTypes.put(long.class.getName(), long.class);
        primitiveTypes.put(float.class.getName(), float.class);
        primitiveTypes.put(double.class.getName(), double.class);
        primitiveTypes.put(void.class.getName(), void.class);
        return Collections.unmodifiableMap(primitiveTypes);
    }

    /**
     * 解析并反序列化参数值列表。
     *
     * @param payload    恢复数据负载
     * @param paramTypes 参数类型数组
     * @return 反序列化后的参数对象数组
     */
    private Object[] resolveArgs(InvocationRecoveryData payload, Class<?>[] paramTypes) {
        if (payload.getArgValues() == null || payload.getArgValues().isEmpty()) {
            return new Object[0];
        }
        Object[] args = new Object[payload.getArgValues().size()];
        for (int i = 0; i < payload.getArgValues().size(); i++) {
            args[i] = serializer.deserialize(paramTypes[i], payload.getArgValues().get(i));
        }
        return args;
    }
}
