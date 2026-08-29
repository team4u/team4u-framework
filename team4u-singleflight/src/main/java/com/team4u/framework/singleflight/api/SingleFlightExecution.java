package com.team4u.framework.singleflight.api;

import com.team4u.framework.base.util.TypeReference;
import lombok.Getter;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Immutable singleflight execution request.
 *
 * @param <T> loader return type
 * @author jay.wu
 */
@Getter
public class SingleFlightExecution<T> {

    private final String point;

    /**
     * Parameter-name to argument-value map, used by criteria and key templates.
     */
    private final Map<String, Object> arguments;

    /**
     * Optional known parameter names used to validate variables as early as possible.
     */
    private final Set<String> parameterNames;

    private final Type returnType;

    private final SingleFlightLoader<T> loader;

    private SingleFlightExecution(String point, Map<String, Object> arguments,
                                  Set<String> parameterNames, Type returnType,
                                  SingleFlightLoader<T> loader) {
        this.point = point;
        this.arguments = arguments == null
                ? Collections.emptyMap() : Collections.unmodifiableMap(arguments);
        this.parameterNames = parameterNames == null
                ? Collections.emptySet() : Collections.unmodifiableSet(parameterNames);
        this.returnType = returnType;
        this.loader = loader;
    }

    public static <T> SingleFlightExecution<T> of(String point, Map<String, Object> arguments,
                                                  Type returnType, ThrowableLoader<T> loader) {
        return of(point, arguments, null, returnType, loader);
    }

    public static <T> SingleFlightExecution<T> of(String point, Map<String, Object> arguments,
                                                  Set<String> parameterNames, Type returnType,
                                                  ThrowableLoader<T> loader) {
        return new SingleFlightExecution<>(point, arguments, parameterNames, returnType,
                new ThrowableLoaderAdapter<>(loader));
    }

    public static <T> SingleFlightExecution<T> of(String point, Map<String, Object> arguments,
                                                  Type returnType, SingleFlightLoader<T> loader) {
        return of(point, arguments, null, returnType, loader);
    }

    public static <T> SingleFlightExecution<T> of(String point, Map<String, Object> arguments,
                                                  Set<String> parameterNames, Type returnType,
                                                  SingleFlightLoader<T> loader) {
        return new SingleFlightExecution<>(point, arguments, parameterNames, returnType, loader);
    }

    public static <T> SingleFlightExecution<T> of(String point, Map<String, Object> arguments,
                                                  TypeReference<T> returnType,
                                                  SingleFlightLoader<T> loader) {
        return of(point, arguments, returnType.getType(), loader);
    }

    @FunctionalInterface
    public interface SingleFlightLoader<T> {

        T load() throws Exception;
    }

    /**
     * Wraps checked loader failures without losing their type at proxy boundaries.
     */
    static final class ThrowableLoaderAdapter<T> implements SingleFlightLoader<T> {

        private final ThrowableLoader<T> loader;

        private ThrowableLoaderAdapter(ThrowableLoader<T> loader) {
            this.loader = loader;
        }

        @Override
        public T load() throws Exception {
            try {
                return loader.load();
            } catch (RuntimeException | Error throwable) {
                throw throwable;
            } catch (Throwable throwable) {
                if (throwable instanceof Exception) {
                    throw (Exception) throwable;
                }
                throw new IllegalStateException(throwable);
            }
        }
    }

    /**
     * Loader used at boundaries where invocation can throw any Throwable.
     */
    @FunctionalInterface
    public interface ThrowableLoader<T> {

        T load() throws Throwable;
    }
}
