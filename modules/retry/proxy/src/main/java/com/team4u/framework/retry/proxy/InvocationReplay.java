package com.team4u.framework.retry.proxy;

import com.team4u.framework.base.util.ReflectUtil;
import com.team4u.framework.base.util.StringUtil;
import com.team4u.framework.base.util.TypeUtil;
import com.team4u.framework.serializer.json.JsonUtil;
import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.retry.proxy.invocation.InvocationArgSnapshot;
import com.team4u.framework.retry.proxy.invocation.InvocationRecoveryData;
import com.team4u.framework.retry.proxy.serialize.JacksonRetryContextSerializer;
import com.team4u.framework.retry.proxy.serialize.RetryContextSerializer;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import com.team4u.framework.retry.managed.recovery.StringRecoveryHandler;
import lombok.Getter;
import lombok.Setter;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * 通用代理方法重试放音机
 * <p>
 * 给基于代理注解模式托管的重试任务使用，负责反射调用真实的目标组件。
 */
public class InvocationReplay implements StringRecoveryHandler {

    public static final String TASK_NAME = "ProxyInvocationReplay";

    @Getter
    @Setter
    private RetryContextSerializer serializer = JacksonRetryContextSerializer.INSTANCE;

    @Override
    public String taskName() {
        return TASK_NAME;
    }

    @Override
    public void recover(String payload, RecoveryContext context) throws Exception {
        InvocationRecoveryData recoveryData = deserializePayload(payload);
        if (recoveryData == null) {
            throw new IllegalArgumentException("InvocationRecoveryData is null");
        }
        validatePayload(recoveryData);
        doRecover(recoveryData);
    }

    private void doRecover(InvocationRecoveryData payload) throws Exception {
        if (payload == null) {
            throw new IllegalArgumentException("InvocationRecoveryData is null");
        }
        validatePayload(payload);

        // 定位目标对象
        Object target = locateTarget(payload.getTargetTypeName(), payload.getTargetBeanName());

        Class<?>[] paramTypes = resolveParamTypes(payload);
        // 复用 AnnotatedMethodResolver 的方法定位与桥接还原（与拦截期解析保持一致）
        Method method = RetryMethodResolver.resolve(
                ReflectUtil.getMethod(Class.forName(payload.getTargetTypeName()), payload.getMethodName(), paramTypes),
                target.getClass()).getEffectiveMethod();

        if (method == null) {
            throw new NoSuchMethodException(payload.getTargetTypeName() + "." + payload.getMethodName());
        }

        Object[] args = resolveArgs(payload, method);

        ReflectUtil.invoke(target, method, args);
    }

    private InvocationRecoveryData deserializePayload(String payload) {
        if (StringUtil.isBlank(payload)) {
            return null;
        }
        return JsonUtil.toBean(payload, InvocationRecoveryData.class);
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
    private Object locateTarget(String targetTypeName, String targetBeanName) throws ClassNotFoundException {
        if (StringUtil.isNotBlank(targetBeanName)) {
            Object namedBean = BeanManager.getInstance().getBean(targetBeanName);
            if (namedBean != null) {
                return namedBean;
            }
            throw new IllegalStateException("No managed bean found for replay target beanName: " + targetBeanName);
        }

        Class<?> clazz = Class.forName(targetTypeName);
        Map<String, ?> beans = BeanManager.getInstance().getBeansOfType(clazz);
        if (beans.size() == 1) {
            return beans.values().iterator().next();
        }
        if (beans.size() > 1) {
            throw new IllegalStateException(
                    "Multiple managed beans found for replay target type: "
                            + targetTypeName + ", candidates=" + beans.keySet());
        }
        Object bean = BeanManager.getInstance().getBean(targetTypeName);
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
        // 支持基本类型名（int/boolean 等）与 void，上收自本类原 PRIMITIVE_TYPES 私有表
        return TypeUtil.forName(typeName);
    }

    /**
     * 解析并反序列化参数值列表。
     *
     * @param payload 恢复数据负载
     * @param method  目标方法
     * @return 反序列化后的参数对象数组
     */
    private Object[] resolveArgs(InvocationRecoveryData payload, Method method) {
        if (payload.getArgs() == null || payload.getArgs().isEmpty()) {
            return new Object[0];
        }
        Class<?>[] paramTypes = method.getParameterTypes();
        Type[] genericParamTypes = method.getGenericParameterTypes();
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
            args[i] = serializer.deserialize(genericParamTypes[i], snapshot.getSerializedValue());
        }
        return args;
    }

    private void validatePayload(InvocationRecoveryData payload) {
        if (StringUtil.isBlank(payload.getTargetTypeName())) {
            throw new IllegalArgumentException(
                    "InvocationRecoveryData.targetTypeName is required. Legacy payloads are no longer supported.");
        }
        if (StringUtil.isBlank(payload.getMethodName())) {
            throw new IllegalArgumentException("InvocationRecoveryData.methodName is required");
        }
        if (payload.getArgs() == null) {
            throw new IllegalArgumentException(
                    "InvocationRecoveryData.args is required. Legacy argTypes/argValues payloads are no longer supported.");
        }
    }
}
