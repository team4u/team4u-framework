package com.team4u.framework.retry.proxy;

import cn.hutool.core.util.ReflectUtil;
import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.retry.domain.store.InvocationArgSnapshot;
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

    @Override
    public String taskName() {
        return TASK_NAME;
    }

    @Override
    public void recover(InvocationRecoveryData payload, RecoveryContext context) throws Exception {
        if (payload == null) {
            throw new IllegalArgumentException("InvocationRecoveryData is null");
        }
        validatePayload(payload);

        // 定位目标对象
        Object target = locateTarget(payload.getTargetTypeName());

        Class<?>[] paramTypes = resolveParamTypes(payload);
        Method method = RetryMethodResolver.findMostSpecificMethod(
                ReflectUtil.getMethod(Class.forName(payload.getTargetTypeName()), payload.getMethodName(), paramTypes),
                target.getClass());
        method = method == null ? null : RetryMethodResolver.resolve(method, target.getClass()).getEffectiveMethod();

        if (method == null) {
            throw new NoSuchMethodException(payload.getTargetTypeName() + "." + payload.getMethodName());
        }

        Object[] args = resolveArgs(payload, paramTypes);

        ReflectUtil.invoke(target, method, args);
    }

    /**
     * 定位目标对象。
     * <p>
     * 仅允许从 {@link BeanManager} 获取 Bean 实例。
     *
     * @param targetTypeName 目标类型名
     * @return 目标对象实例
     * @throws ClassNotFoundException 如果找不到对应的类
     */
    private Object locateTarget(String targetTypeName) throws ClassNotFoundException {
        Class<?> clazz = Class.forName(targetTypeName);
        Object bean = BeanManager.getInstance().getBean(clazz);
        if (bean != null) {
            return bean;
        }
        bean = BeanManager.getInstance().getBean(targetTypeName);
        if (bean != null) {
            return bean;
        }
        throw new IllegalStateException("No managed bean found for replay target: " + targetTypeName);
    }

    /**
     * 解析参数类型列表。
     *
     * @param payload 恢复数据负载
     * @return 参数类型数组
     * @throws ClassNotFoundException 如果参数类型类不存在
     */
    private Class<?>[] resolveParamTypes(InvocationRecoveryData payload) throws ClassNotFoundException {
        if (payload.getArgs() == null || payload.getArgs().isEmpty()) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[payload.getArgs().size()];
        for (int i = 0; i < payload.getArgs().size(); i++) {
            InvocationArgSnapshot snapshot = payload.getArgs().get(i);
            if (snapshot == null || snapshot.getTypeName() == null) {
                throw new IllegalArgumentException("InvocationRecoveryData.args[" + i + "] missing typeName");
            }
            types[i] = resolveType(snapshot.getTypeName());
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

    /**
     * 解析并反序列化参数值列表。
     *
     * @param payload    恢复数据负载
     * @param paramTypes 参数类型数组
     * @return 反序列化后的参数对象数组
     */
    private Object[] resolveArgs(InvocationRecoveryData payload, Class<?>[] paramTypes) {
        if (payload.getArgs() == null || payload.getArgs().isEmpty()) {
            return new Object[0];
        }
        if (payload.getArgs().size() != paramTypes.length) {
            throw new IllegalArgumentException("InvocationRecoveryData args length mismatch. snapshots="
                    + payload.getArgs().size() + ", paramTypes=" + paramTypes.length);
        }
        Object[] args = new Object[payload.getArgs().size()];
        for (int i = 0; i < payload.getArgs().size(); i++) {
            InvocationArgSnapshot snapshot = payload.getArgs().get(i);
            if (snapshot.isIgnored()) {
                if (paramTypes[i].isPrimitive()) {
                    throw new IllegalArgumentException("Ignored primitive parameter cannot be replayed at index " + i);
                }
                args[i] = null;
                continue;
            }
            args[i] = serializer.deserialize(paramTypes[i], snapshot.getSerializedValue());
        }
        return args;
    }

    private void validatePayload(InvocationRecoveryData payload) {
        if (payload.getTargetTypeName() == null || payload.getTargetTypeName().trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "InvocationRecoveryData.targetTypeName is required. Legacy payloads are no longer supported.");
        }
        if (payload.getMethodName() == null || payload.getMethodName().trim().isEmpty()) {
            throw new IllegalArgumentException("InvocationRecoveryData.methodName is required");
        }
        if (payload.getArgs() == null) {
            throw new IllegalArgumentException(
                    "InvocationRecoveryData.args is required. Legacy argTypes/argValues payloads are no longer supported.");
        }
    }
}
