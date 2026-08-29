package com.team4u.framework.singleflight.proxy;

import com.team4u.framework.base.util.ReflectUtil;
import com.team4u.framework.base.util.TypeUtil;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
import com.team4u.framework.proxy.support.AnnotatedMethodResolver;
import com.team4u.framework.singleflight.api.SingleFlightException;
import com.team4u.framework.singleflight.api.SingleFlightExecution;
import com.team4u.framework.singleflight.api.SingleFlights;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拦截 {@link SingleFlight} 注解方法，组装类型化执行请求并转发给
 * {@link SingleFlights} 背后的引擎。
 * <p>
 * 注解解析统一委托 {@link AnnotatedMethodResolver} 按 (method, targetClass)
 * 解析与缓存：此前实现只查方法声明链不查 targetClass，JDK 接口代理下拦截到的
 * 是接口方法、注解仅标注在实现方法时被误判为未注解而直通——现于
 * targetClass 继承体系中最具体的方法上优先命中，两种标注位置均正确生效。
 * 方法元数据（注解、参数信息）按 Method + targetClass 缓存，代理调用只做一次组装：
 * 参数名 → 参数值 Map、泛型返回类型与可抛任意 Throwable 的加载函数。
 * 组件异常可经 {@link SingleFlightExceptionHandler} 转换为方法兼容的返回值，
 * 转换结果做类型与 null 安全校验（基本类型按包装类比较，
 * {@link TypeUtil#wrap(Class)}），保证代理透明性。
 * </p>
 *
 * @author jay.wu
 */
public class SingleFlightInterceptor implements MethodInterceptor {

    /**
     * 注解解析器：注解查找与缓存收敛于公共设施（含 targetClass 实现方法查找）
     */
    private static final AnnotatedMethodResolver<SingleFlight> RESOLVER =
            AnnotatedMethodResolver.of(SingleFlight.class);

    private final SingleFlightExceptionHandler exceptionHandler;
    /**
     * 方法元数据缓存：注解解析与参数名提取只执行一次
     */
    private final Map<Method, MethodMeta> metaCache = new ConcurrentHashMap<>();

    public SingleFlightInterceptor() {
        this(null);
    }

    public SingleFlightInterceptor(SingleFlightExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Class<?> targetClass = invocation.getTarget() == null
                ? method.getDeclaringClass() : invocation.getTarget().getClass();
        MethodMeta meta = metaCache.computeIfAbsent(method, m -> buildMeta(m, targetClass));
        // 未标注解的方法直通，代理对非协调方法零侵入
        if (!meta.annotated) {
            return invocation.proceed();
        }
        Map<String, Object> arguments = arguments(meta.parameters, invocation.getArguments());
        // ThrowableLoader 适配代理边界：方法调用可抛任意 Throwable
        SingleFlightExecution.ThrowableLoader<Object> loader = invocation::proceed;
        SingleFlightExecution<Object> execution = SingleFlightExecution.of(
                meta.point, arguments, meta.parameterNames,
                method.getGenericReturnType(), loader);
        try {
            return SingleFlights.execute(execution);
        } catch (SingleFlightException e) {
            if (exceptionHandler == null) {
                throw e;
            }
            return handleComponentException(method, arguments, e);
        }
    }

    /**
     * 经处理器转换组件异常：受检异常需与方法签名兼容（不兼容包为 IllegalStateException），
     * 返回值需满足方法的 null 安全与类型约束。
     */
    private Object handleComponentException(Method method, Map<String, Object> arguments,
                                            SingleFlightException exception) throws Throwable {
        Object result;
        try {
            result = exceptionHandler.handle(method, method.getGenericReturnType(),
                    exception, arguments);
        } catch (RuntimeException | Error throwable) {
            throw throwable;
        } catch (Throwable throwable) {
            if (declaresCheckedException(method, throwable)) {
                throw throwable;
            }
            throw new IllegalStateException("SingleFlight exception handler threw an undeclared "
                    + "checked exception|method=" + method, throwable);
        }
        validateHandlerResult(method, result);
        return result;
    }

    /**
     * 方法签名是否声明了该受检异常（处理器上抛受检异常的兼容性边界）。
     */
    private boolean declaresCheckedException(Method method, Throwable throwable) {
        for (Class<?> type : method.getExceptionTypes()) {
            if (type.isInstance(throwable)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析方法元数据：无注解直通；有注解时要求参数名可读（-parameters），
     * 否则 key 模板与 skipWhen 将失去变量来源，直接在代理创建期失败。
     */
    private MethodMeta buildMeta(Method method, Class<?> targetClass) {
        SingleFlight annotation = resolveAnnotation(method, targetClass);
        if (annotation == null) {
            return MethodMeta.passThrough(method);
        }
        Parameter[] parameters = ReflectUtil.getParameters(targetClass, method);
        if (method.getParameterCount() > 0
                && (parameters == null || !parameters[0].isNamePresent())) {
            throw new IllegalStateException(
                    "@SingleFlight requires -parameters and readable parameter names|method=" + method);
        }
        Set<String> names = new LinkedHashSet<>();
        if (parameters != null) {
            for (Parameter parameter : parameters) {
                names.add(parameter.getName());
            }
        }
        return new MethodMeta(annotation.value(), parameters, names, true);
    }

    /**
     * 组装参数名 → 参数值映射（保持参数声明顺序）。
     */
    private Map<String, Object> arguments(Parameter[] parameters, Object[] values) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (parameters == null || values == null) {
            return arguments;
        }
        for (int i = 0; i < parameters.length && i < values.length; i++) {
            arguments.put(parameters[i].getName(), values[i]);
        }
        return arguments;
    }

    /**
     * 校验处理器返回值：基本类型方法不允许 null，返回值必须可赋值给方法返回类型
     * （基本类型按包装类比较，经 {@link TypeUtil#wrap(Class)}）。
     */
    private void validateHandlerResult(Method method, Object result) {
        Class<?> returnType = method.getReturnType();
        if (result == null) {
            if (returnType.isPrimitive()) {
                throw new IllegalStateException(
                        "SingleFlight exception handler returned null for primitive method|method=" + method);
            }
            return;
        }
        if (!TypeUtil.wrap(returnType).isInstance(result)) {
            throw new IllegalStateException(
                    "SingleFlight exception handler returned incompatible type|method=" + method
                            + "|expected=" + returnType.getName()
                            + "|actual=" + result.getClass().getName());
        }
    }

    /**
     * 解析方法上生效的 {@link SingleFlight} 注解：经 {@link AnnotatedMethodResolver}
     * 按 (method, targetClass) 解析——targetClass 继承体系中最具体的方法（含桥接还原）、
     * 方法自身、接口与父类链同名同参方法依次查找，注解可集中声明在接口或父类方法上。
     */
    public static SingleFlight resolveAnnotation(Method method, Class<?> targetClass) {
        return RESOLVER.resolve(method, targetClass);
    }

    /**
     * 方法元数据：注解 point、参数信息与参数名集合；未注解方法标记直通。
     */
    private static final class MethodMeta {

        private final String point;
        private final Parameter[] parameters;
        private final Set<String> parameterNames;
        private final boolean annotated;

        private MethodMeta(String point, Parameter[] parameters,
                           Set<String> parameterNames, boolean annotated) {
            this.point = point;
            this.parameters = parameters;
            this.parameterNames = parameterNames;
            this.annotated = annotated;
        }

        private static MethodMeta passThrough(Method method) {
            return new MethodMeta(null, method.getParameters(), new LinkedHashSet<>(), false);
        }
    }
}
