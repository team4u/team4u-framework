package com.team4u.framework.singleflight.proxy;

import com.team4u.framework.base.util.ReflectUtil;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;
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
 * Intercepts {@link SingleFlight} methods and forwards a typed execution to the
 * engine behind {@link SingleFlights}.
 *
 * @author jay.wu
 */
public class SingleFlightInterceptor implements MethodInterceptor {

    private final SingleFlightExceptionHandler exceptionHandler;
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
        MethodMeta meta = metaCache.computeIfAbsent(method, this::buildMeta);
        if (!meta.annotated) {
            return invocation.proceed();
        }
        Map<String, Object> arguments = arguments(meta.parameters, invocation.getArguments());
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

    private boolean declaresCheckedException(Method method, Throwable throwable) {
        for (Class<?> type : method.getExceptionTypes()) {
            if (type.isInstance(throwable)) {
                return true;
            }
        }
        return false;
    }

    private MethodMeta buildMeta(Method method) {
        SingleFlight annotation = resolveAnnotation(method);
        if (annotation == null) {
            return MethodMeta.passThrough(method);
        }
        Parameter[] parameters = ReflectUtil.getParameters(method.getDeclaringClass(), method);
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

    private void validateHandlerResult(Method method, Object result) {
        Class<?> returnType = method.getReturnType();
        if (result == null) {
            if (returnType.isPrimitive()) {
                throw new IllegalStateException(
                        "SingleFlight exception handler returned null for primitive method|method=" + method);
            }
            return;
        }
        if (!box(returnType).isInstance(result)) {
            throw new IllegalStateException(
                    "SingleFlight exception handler returned incompatible type|method=" + method
                            + "|expected=" + returnType.getName()
                            + "|actual=" + result.getClass().getName());
        }
    }

    private Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        return Integer.class;
    }

    public static SingleFlight resolveAnnotation(Method method) {
        SingleFlight annotation = method.getAnnotation(SingleFlight.class);
        if (annotation != null) {
            return annotation;
        }
        for (Class<?> intf : method.getDeclaringClass().getInterfaces()) {
            SingleFlight found = findAnnotation(method, intf);
            if (found != null) {
                return found;
            }
        }
        Class<?> superclass = method.getDeclaringClass().getSuperclass();
        if (superclass != null && superclass != Object.class) {
            SingleFlight found = findAnnotation(method, superclass);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static SingleFlight findAnnotation(Method method, Class<?> type) {
        try {
            return type.getMethod(method.getName(), method.getParameterTypes())
                    .getAnnotation(SingleFlight.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

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
