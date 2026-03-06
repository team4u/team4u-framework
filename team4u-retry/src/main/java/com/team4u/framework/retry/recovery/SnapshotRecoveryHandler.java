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
 * 基于 RetryTaskSnapshot 的通用恢复处理器。
 */
public class SnapshotRecoveryHandler implements RecoveryHandler {

    private final String key;

    @Setter
    private RetryTaskSnapshotSerializer snapshotSerializer = HutoolRetryTaskSnapshotSerializer.INSTANCE;

    public SnapshotRecoveryHandler(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public void recover(String payload) throws Exception {
        RetryTaskSnapshot snapshot = snapshotSerializer.deserialize(payload);
        if (snapshot == null) {
            throw new IllegalArgumentException("Retry task snapshot must not be null");
        }

        Object bean = resolveBean(snapshot.getBeanName());
        Method method = resolveMethod(bean.getClass(), snapshot.getMethodName(), snapshot.getArgTypes());
        Object[] args = deserializeArgs(snapshot.getArgTypes(), snapshot.getArgJsonValues());

        RecoveryExecutionContext.run((RecoveryExecutionContext.CheckedRunnable) () -> invoke(bean, method, args));
    }

    protected Object resolveBean(String beanName) {
        if (beanName == null || beanName.trim().isEmpty()) {
            throw new IllegalArgumentException("Snapshot beanName must not be blank");
        }

        BeanManager beanManager = BeanManager.getInstance();
        Object bean = beanManager.getBean(beanName);
        if (bean != null) {
            return bean;
        }

        try {
            Class<?> beanType = Class.forName(beanName);
            bean = beanManager.getBean(beanType);
            if (bean != null) {
                return bean;
            }
        } catch (ClassNotFoundException ignored) {
            // ignore and fall through
        }

        throw new IllegalStateException("Cannot resolve bean from BeanManager. beanName=" + beanName);
    }

    protected Method resolveMethod(Class<?> beanClass, String methodName, List<String> argTypeNames) {
        Class<?>[] argTypes = toClasses(argTypeNames);
        try {
            Method method = beanClass.getMethod(methodName, argTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Cannot resolve recovery method. class=" + beanClass.getName()
                            + ", method=" + methodName
                            + ", argTypes=" + argTypeNames, e);
        }
    }

    protected Object[] deserializeArgs(List<String> argTypeNames, List<String> argJsonValues) {
        if (argTypeNames == null || argTypeNames.isEmpty()) {
            return new Object[0];
        }
        if (argJsonValues == null || argJsonValues.size() != argTypeNames.size()) {
            throw new IllegalArgumentException(
                    "Snapshot argJsonValues size must match argTypes size. argTypes="
                            + argTypeNames.size() + ", argJsonValues="
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

    protected Object deserializeArg(Class<?> argType, String json) {
        if (json == null) {
            return null;
        }
        if (isSimpleType(argType)) {
            Object value = JSONUtil.parseArray("[" + json + "]").get(0);
            if (argType == char.class || argType == Character.class) {
                String text = Convert.toStr(value);
                return text == null || text.isEmpty() ? '\0' : text.charAt(0);
            }
            return Convert.convert(argType, value);
        }
        return JSONUtil.toBean(json, argType);
    }

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
            throw new IllegalStateException("Failed to invoke recovery method: " + method, e);
        }
    }

    protected Class<?>[] toClasses(List<String> typeNames) {
        Class<?>[] types = new Class<?>[typeNames == null ? 0 : typeNames.size()];
        for (int i = 0; i < types.length; i++) {
            types[i] = toClass(typeNames.get(i));
        }
        return types;
    }

    protected Class<?> toClass(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Argument type name must not be blank");
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
            throw new IllegalStateException("Cannot load argument type: " + name, e);
        }
    }
}