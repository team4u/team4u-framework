package com.team4u.framework.retry.proxy;

import lombok.Data;

import java.lang.reflect.Method;

/**
 * 重试方法元数据解析器
 * <p>
 * 负责解析方法调用时的元数据，包括查找具体实现方法、处理桥接方法以及查找 {@link Retryable} 注解。
 * 该解析器能够确保在继承体系、泛型及接口代理等复杂场景下，准确识别重试配置并找到对应的执行逻辑。
 *
 * @author team4u
 */
public final class RetryMethodResolver {

    private RetryMethodResolver() {
    }

    /**
     * 解析目标方法的重试元数据信息
     *
     * @param invocationMethod 拦截到的原始方法
     * @param targetClass      具体的执行目标对象类型
     * @return 解析后的元数据封装对象，包含实际执行的方法、重试注解及其恢复目标类型
     */
    public static ResolvedRetryMethod resolve(Method invocationMethod, Class<?> targetClass) {
        // 在类层次结构中，查找比代理方法更具体的实现方法
        Method specificMethod = findMostSpecificMethod(invocationMethod, targetClass);
        // 如果查找到的是由于泛型参数擦除导致的桥接方法，则将其还原为原本定义的业务方法
        Method effectiveMethod = resolveBridgeMethod(specificMethod);
        // 按照优先级顺序查找到最匹配的重试注解（方法级优先于类级，具体类优先于接口）
        Retryable retryable = findRetryable(invocationMethod, effectiveMethod, targetClass);
        // 确定异常恢复逻辑所需的目标上下文类型
        Class<?> recoveryTargetType = resolveRecoveryTargetType(invocationMethod, effectiveMethod, targetClass);
        return new ResolvedRetryMethod(effectiveMethod, retryable, recoveryTargetType);
    }

    /**
     * 在目标类的继承体系中查找最匹配的方法实现
     *
     * @param method      基准方法
     * @param targetClass 要搜索的目标类
     * @return 查找到的具体方法实现。若目标类为空或未找到匹配项，则保持返回原方法。
     */
    public static Method findMostSpecificMethod(Method method, Class<?> targetClass) {
        if (method == null || targetClass == null) {
            return method;
        }
        Method candidate = findMethodInHierarchy(targetClass, method.getName(), method.getParameterTypes());
        return candidate != null ? candidate : method;
    }

    /**
     * 递归遍历类及其父类和实现的接口，尝试根据名称和参数签名匹配申报的方法
     */
    private static Method findMethodInHierarchy(Class<?> type, String name, Class<?>[] parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                // 在当前类型的声明方法中进行精确查找
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // 如果当前类未找到，则尝试从其直接继承的父类中继续向上追溯
                current = current.getSuperclass();
            }
        }
        // 如果在类继承路径中未查找到，则递归遍历该类型实现的所有接口进行查找
        for (Class<?> interfaceType : type.getInterfaces()) {
            Method method = findMethodInHierarchy(interfaceType, name, parameterTypes);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    /**
     * 解析并还原 Java 桥接方法
     * <p>
     * 在涉及泛型和子类覆盖时，编译器会生成对应的桥接方法。
     * 本方法旨在通过签名匹配，查找到桥接方法背后的业务目标方法。
     */
    private static Method resolveBridgeMethod(Method method) {
        if (method == null || !method.isBridge()) {
            return method;
        }
        // 获取声明该桥接方法的所有方法，在其中寻找真正的业务实现
        Method[] declaredMethods = method.getDeclaringClass().getDeclaredMethods();
        for (Method candidate : declaredMethods) {
            // 跳过其他的桥接方法，避免陷入产生歧义
            if (candidate.isBridge()) {
                continue;
            }
            // 匹配方法名是否一致
            if (!candidate.getName().equals(method.getName())) {
                continue;
            }
            // 匹配参数项数量是否完全一致
            if (candidate.getParameterCount() != method.getParameterCount()) {
                continue;
            }
            // 校验返回类型是否具有继承或相同的兼容性
            if (!method.getReturnType().isAssignableFrom(candidate.getReturnType())) {
                continue;
            }
            // 对参数类型进行逐位比对，确保签名严谨匹配
            if (matchesBridgeSignature(method, candidate)) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        return method;
    }

    /**
     * 判断候选方法是否与指定的桥接方法在参数签名上逻辑匹配
     */
    private static boolean matchesBridgeSignature(Method bridgeMethod, Method candidate) {
        Class<?>[] bridgeParameterTypes = bridgeMethod.getParameterTypes();
        Class<?>[] candidateParameterTypes = candidate.getParameterTypes();
        for (int i = 0; i < bridgeParameterTypes.length; i++) {
            // 检查桥接方法对应位置的参数类型是否通过向上转型能够容纳候选方法的参数类型
            if (!bridgeParameterTypes[i].isAssignableFrom(candidateParameterTypes[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 查找重试标记注解
     * <p>
     * 遵循查找路径：直接方法 -> 执行方法 -> 目标类 -> 声明类
     */
    private static Retryable findRetryable(Method invocationMethod, Method effectiveMethod, Class<?> targetClass) {
        // 首先尝试直接从拦截到的方法声明中提取注解信息
        Retryable retryable = invocationMethod.getAnnotation(Retryable.class);
        if (retryable != null) {
            return retryable;
        }
        // 如果拦截方法未标记，且执行方法与其不同，则针对执行方法进行查找
        if (effectiveMethod != null && effectiveMethod != invocationMethod) {
            retryable = effectiveMethod.getAnnotation(Retryable.class);
            if (retryable != null) {
                return retryable;
            }
        }
        // 尝试检查具体的目标实现类是否标记了重试注解
        if (targetClass != null) {
            retryable = targetClass.getAnnotation(Retryable.class);
            if (retryable != null) {
                return retryable;
            }
        }
        // 最后回退到原始申报该方法的父类或接口中进行查找
        return invocationMethod.getDeclaringClass().getAnnotation(Retryable.class);
    }

    /**
     * 解析用于异常发生后寻找恢复执行点的目标类型
     */
    private static Class<?> resolveRecoveryTargetType(
            Method invocationMethod,
            Method effectiveMethod,
            Class<?> targetClass) {
        if (effectiveMethod != null && effectiveMethod.getDeclaringClass() != Object.class) {
            return effectiveMethod.getDeclaringClass();
        }
        if (targetClass != null) {
            return targetClass;
        }
        if (invocationMethod != null && invocationMethod.getDeclaringClass() != Object.class) {
            return invocationMethod.getDeclaringClass();
        }
        return null;
    }

    /**
     * 封装解析后的重试执行元数据
     */
    @Data
    public static final class ResolvedRetryMethod {
        /**
         * 实际参与业务执行的方法句柄
         */
        private final Method effectiveMethod;
        /**
         * 具体生效的重试注解配置
         */
        private final Retryable retryable;
        /**
         * 关联对应的恢复操作目标类
         */
        private final Class<?> recoveryTargetType;
    }
}
