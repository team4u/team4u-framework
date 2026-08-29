package com.team4u.framework.retry.proxy;

import com.team4u.framework.proxy.support.AnnotatedMethodResolver;
import lombok.Data;

import java.lang.reflect.Method;

/**
 * 重试方法元数据解析器
 * <p>
 * 注解查找与桥接方法还原逻辑已上收至
 * {@link AnnotatedMethodResolver}（以本类原实现语义为基底泛化，结果按
 * (method, targetClass) 缓存），本类仅保留重试特有的「恢复目标类型」推导，
 * 以及 {@link RetryDelegate} 所需的三元组封装。
 * <p>
 * 注解解析顺序与原实现语义等价：targetClass 最具体方法（含桥接还原）→
 * 拦截方法自身 → 接口/父类链 → 类级注解。解析不到注解时返回的
 * {@link ResolvedRetryMethod#getRetryable()} 为 null，调用方据此直通业务逻辑。
 *
 * @author team4u
 */
public final class RetryMethodResolver {

    /**
     * {@link Retryable} 注解解析器（共享缓存）
     */
    private static final AnnotatedMethodResolver<Retryable> RESOLVER =
            AnnotatedMethodResolver.of(Retryable.class);

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
        // 在类层次结构中，查找比代理方法更具体的实现方法，并还原泛型擦除产生的桥接方法
        Method effectiveMethod = RESOLVER.resolveBridgeMethod(
                RESOLVER.findMostSpecificMethod(invocationMethod, targetClass));
        // 按照优先级顺序查找到最匹配的重试注解（方法级优先于类级，具体类优先于接口）
        Retryable retryable = RESOLVER.resolve(invocationMethod, targetClass);
        // 确定异常恢复逻辑所需的目标上下文类型
        Class<?> recoveryTargetType = resolveRecoveryTargetType(invocationMethod, effectiveMethod, targetClass);
        return new ResolvedRetryMethod(effectiveMethod, retryable, recoveryTargetType);
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
