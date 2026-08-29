package com.team4u.framework.singleflight.api;

import com.team4u.framework.base.util.TypeReference;
import lombok.Getter;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 一次不可变的回源合并执行请求，编程式 API 的唯一入参。
 * <p>
 * 携带协调所需的全部上下文：切入点（point）、参数名到参数值的 Map（供条件匹配与
 * key 模板渲染）、目标返回类型（供结果反序列化与降级转换）以及加载函数。
 * arguments 与 parameterNames 在构造时即冻结为不可变视图。
 * </p>
 *
 * @param <T> 加载函数返回类型
 * @author jay.wu
 */
@Getter
public class SingleFlightExecution<T> {

    private final String point;

    /**
     * 参数名 → 参数值映射，供 skipWhen / cacheWhen 条件匹配与 key 模板渲染使用。
     */
    private final Map<String, Object> arguments;

    /**
     * 可选的已知参数名集合，用于在执行前尽早校验条件表达式变量的可解析性。
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

    /**
     * 加载函数：真正执行回源动作（DB / RPC 等），可抛受检异常。
     *
     * @param <T> 返回类型
     */
    @FunctionalInterface
    public interface SingleFlightLoader<T> {

        T load() throws Exception;
    }

    /**
     * ThrowableLoader 的适配器：在代理边界把“可抛任意 Throwable”的加载函数收窄为
     * “可抛 Exception”，受检异常类型在边界处不丢失，非 Exception 的 Throwable 包为
     * IllegalStateException 继续上抛。
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
     * 代理边界使用的加载函数：方法调用可抛任意 Throwable（如方法签名声明的受检异常）。
     *
     * @param <T> 返回类型
     */
    @FunctionalInterface
    public interface ThrowableLoader<T> {

        T load() throws Throwable;
    }
}
