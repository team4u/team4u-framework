package com.team4u.framework.proxy.support;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 注解方法统一解析器
 * <p>
 * 以 retry 模块 RetryMethodResolver 的语义为基底泛化而成，收敛各模块
 * （ratelimiter/singleflight/retry/log/router）各自实现的注解查找逻辑。
 * 对给定的 (method, targetClass) 二元组解析方法上生效的注解实例，
 * 按以下优先级查找（最具体者优先，保证 JDK 代理与 ByteBuddy 子类代理行为一致）：
 * <ol>
 *     <li>targetClass 继承体系中最具体的方法（含桥接方法还原）——JDK 接口代理下，
 *     拦截到的是接口方法，注解仅标注在实现方法时由此命中（singleflight 漏判 bug 的修复点）</li>
 *     <li>拦截到的方法自身——注解直接标注在接口方法上的场景</li>
 *     <li>targetClass 的父类与接口链上的同名同参方法——注解仅在接口/父类方法声明的场景</li>
 *     <li>方法声明类的父类与接口链上的同名同参方法</li>
 *     <li>类级注解：targetClass（支持 @Inherited 向上传递），其次方法声明类</li>
 * </ol>
 * 注解类型本身仍由各业务模块自行定义，本类不依赖任何业务注解。
 * 解析结果按 (method, targetClass) 缓存，并发场景下每个键只解析一次。
 * <p>
 * 对于仅允许标注在方法上的注解（@Target(METHOD)），第 5 步类级查找恒为空，
 * 不影响正确性；对于方法与类均可标注的注解，方法级注解优先于类级。
 *
 * @param <A> 目标注解类型
 * @author jay.wu
 */
public class AnnotatedMethodResolver<A extends Annotation> {

    /**
     * 缓存未命中注解时的占位哨兵（ConcurrentHashMap 不允许 null 值）
     */
    private static final Object ABSENT = new Object();

    private final Class<A> annotationType;

    /**
     * 解析结果缓存：key 为 method + targetClass 二元组
     */
    private final ConcurrentMap<CacheKey, Object> cache = new ConcurrentHashMap<>();

    /**
     * @param annotationType 目标注解类型，不可为 null
     */
    public AnnotatedMethodResolver(Class<A> annotationType) {
        if (annotationType == null) {
            throw new IllegalArgumentException("annotationType cannot be null");
        }
        this.annotationType = annotationType;
    }

    /**
     * 静态工厂：为指定注解类型创建解析器
     *
     * @param annotationType 目标注解类型
     * @param <A>            注解类型泛型
     * @return 解析器实例
     */
    public static <A extends Annotation> AnnotatedMethodResolver<A> of(Class<A> annotationType) {
        return new AnnotatedMethodResolver<A>(annotationType);
    }

    /**
     * 解析方法上生效的注解实例
     *
     * @param method      拦截到的方法（可能是接口方法或桥接方法）
     * @param targetClass 具体的执行目标类型（JDK 代理场景必须传入实现类才能命中实现方法上的注解）
     * @return 生效的注解实例；未找到或 method 为 null 时返回 null
     */
    public A resolve(Method method, Class<?> targetClass) {
        if (method == null) {
            return null;
        }
        Object resolved = cache.computeIfAbsent(
                new CacheKey(method, targetClass),
                key -> doResolve(key.method, key.targetClass));
        return resolved == ABSENT ? null : annotationType.cast(resolved);
    }

    /**
     * 方法（结合目标类型）是否标注了目标注解
     *
     * @param method      拦截到的方法
     * @param targetClass 具体的执行目标类型
     * @return true 若解析出注解实例
     */
    public boolean isAnnotated(Method method, Class<?> targetClass) {
        return resolve(method, targetClass) != null;
    }

    /**
     * @return 目标注解类型
     */
    public Class<A> getAnnotationType() {
        return annotationType;
    }

    /**
     * 在目标类的继承体系中查找最匹配的方法实现
     * <p>
     * 语义与 retry 模块的实现一致：沿父类链精确匹配（含非公有方法），
     * 未命中时递归遍历实现的接口。
     *
     * @param method      基准方法
     * @param targetClass 要搜索的目标类
     * @return 查找到的具体方法实现。若目标类为空或未找到匹配项，则保持返回原方法。
     */
    public Method findMostSpecificMethod(Method method, Class<?> targetClass) {
        if (method == null || targetClass == null) {
            return method;
        }
        Method candidate = findMethodInHierarchy(targetClass, method.getName(), method.getParameterTypes());
        return candidate != null ? candidate : method;
    }

    /**
     * 解析并还原 Java 桥接方法
     * <p>
     * 在涉及泛型和子类覆盖时，编译器会生成对应的桥接方法。
     * 本方法通过签名匹配，查找到桥接方法背后的业务目标方法（其上才标注真实注解）。
     *
     * @param method 可能是桥接方法的方法
     * @return 桥接方法对应的业务方法；非桥接方法或无法还原时返回原方法
     */
    public Method resolveBridgeMethod(Method method) {
        if (method == null || !method.isBridge()) {
            return method;
        }
        Method[] declaredMethods = method.getDeclaringClass().getDeclaredMethods();
        for (Method candidate : declaredMethods) {
            if (candidate.isBridge()) {
                continue;
            }
            if (!candidate.getName().equals(method.getName())) {
                continue;
            }
            if (candidate.getParameterCount() != method.getParameterCount()) {
                continue;
            }
            if (!method.getReturnType().isAssignableFrom(candidate.getReturnType())) {
                continue;
            }
            if (matchesBridgeSignature(method, candidate)) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        return method;
    }

    /**
     * 执行实际的多级查找（结果写入缓存）
     */
    private Object doResolve(Method method, Class<?> targetClass) {
        // 1. targetClass 继承体系中最具体的方法（先还原桥接）：JDK 代理下注解仅在实现方法时由此命中
        Method specificMethod = findMostSpecificMethod(method, targetClass);
        Method effectiveMethod = resolveBridgeMethod(specificMethod);
        if (effectiveMethod != null) {
            A found = effectiveMethod.getAnnotation(annotationType);
            if (found != null) {
                return found;
            }
        }
        // 2. 拦截到的方法自身：注解直接标注在接口方法（或实现方法）上的场景
        A found = method.getAnnotation(annotationType);
        if (found != null) {
            return found;
        }
        // 3. targetClass 的父类与接口链：注解仅声明在接口/父类方法上的场景
        found = findOnClass(targetClass, method.getName(), method.getParameterTypes());
        if (found != null) {
            return found;
        }
        // 4. 方法声明类的父类与接口链
        found = findOnClass(method.getDeclaringClass(), method.getName(), method.getParameterTypes());
        if (found != null) {
            return found;
        }
        // 5. 类级注解：targetClass 优先（支持 @Inherited），其次方法声明类
        if (targetClass != null) {
            found = targetClass.getAnnotation(annotationType);
            if (found != null) {
                return found;
            }
        }
        found = method.getDeclaringClass().getAnnotation(annotationType);
        return found != null ? found : ABSENT;
    }

    /**
     * 递归遍历类及其父类和实现的接口，尝试根据名称和参数签名匹配申报的方法
     */
    private Method findMethodInHierarchy(Class<?> type, String name, Class<?>[] parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        for (Class<?> interfaceType : type.getInterfaces()) {
            Method method = findMethodInHierarchy(interfaceType, name, parameterTypes);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    /**
     * 沿类的接口与父类链查找同名同参公有方法上的注解（语义对齐 ratelimiter 现状实现）
     */
    private A findOnClass(Class<?> clazz, String methodName, Class<?>[] paramTypes) {
        if (clazz == null) {
            return null;
        }
        try {
            Method candidate = clazz.getMethod(methodName, paramTypes);
            A annotation = candidate.getAnnotation(annotationType);
            if (annotation != null) {
                return annotation;
            }
        } catch (NoSuchMethodException ignored) {
            // 该类未声明此方法，继续查接口与父类
        }
        for (Class<?> intf : clazz.getInterfaces()) {
            A annotation = findOnClass(intf, methodName, paramTypes);
            if (annotation != null) {
                return annotation;
            }
        }
        return findOnClass(clazz.getSuperclass(), methodName, paramTypes);
    }

    /**
     * 判断候选方法是否与指定的桥接方法在参数签名上逻辑匹配
     */
    private boolean matchesBridgeSignature(Method bridgeMethod, Method candidate) {
        Class<?>[] bridgeParameterTypes = bridgeMethod.getParameterTypes();
        Class<?>[] candidateParameterTypes = candidate.getParameterTypes();
        for (int i = 0; i < bridgeParameterTypes.length; i++) {
            if (!bridgeParameterTypes[i].isAssignableFrom(candidateParameterTypes[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析缓存键：method + targetClass 二元组
     */
    private static final class CacheKey {

        private final Method method;
        private final Class<?> targetClass;

        private CacheKey(Method method, Class<?> targetClass) {
            this.method = method;
            this.targetClass = targetClass;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            CacheKey cacheKey = (CacheKey) o;
            return method.equals(cacheKey.method) && Objects.equals(targetClass, cacheKey.targetClass);
        }

        @Override
        public int hashCode() {
            return Objects.hash(method, targetClass);
        }
    }
}
