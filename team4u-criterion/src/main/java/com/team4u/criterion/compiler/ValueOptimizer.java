package com.team4u.criterion.compiler;

import com.team4u.criterion.MatchContext;
import com.team4u.criterion.model.value.FixedValue;
import com.team4u.criterion.model.value.Value;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 编译期值优化器
 * <p>
 * 负责在编译期将 {@link Value} 对象转换为无分支的高效 {@link CompiledValue}。
 * 核心思想是将"取值"和"类型转换"组装成一个高阶函数（闭包），
 * 在编译期就决定好这个闭包是"直接返回常量"还是"运行时去 Context 里取并转换"。
 * <p>
 * 使用场景：
 * <pre>{@code
 * // 优化单个值
 * CompiledValue<Double> thresholdGetter = ValueOptimizer.optimize(
 *     criterion.getThreshold(),
 *     num -> num == null ? -1.0 : num.doubleValue()
 * );
 *
 * // 优化集合值
 * CompiledValue<Set<Object>> expectedSetGetter = ValueOptimizer.optimizeToSet(criterion.getValues());
 * }</pre>
 *
 * @author jay.wu
 */
public final class ValueOptimizer {

    private ValueOptimizer() {
        // 工具类，禁止实例化
    }

    /**
     * 优化单个标量值 (Scalar)
     * <p>
     * 如果 provider 是 {@link FixedValue}，则在编译期提前取值并完成转换，
     * 返回的 CompiledValue 在运行时直接返回内存中的常量引用。
     * <p>
     * 如果 provider 是动态值，则将提取和转换逻辑推迟到运行时。
     *
     * @param provider  原始的 Value 提供者
     * @param converter 类型转换逻辑 (如: Object -> Double)
     * @param <T>       原始类型
     * @param <R>       目标转换类型
     * @return 编译后的取值器
     */
    public static <T, R> CompiledValue<R> optimize(Value<T> provider, Function<T, R> converter) {
        // 【静态分支】：编译期提前取值并完成转换！
        if (provider instanceof FixedValue) {
            T rawValue = provider.get(null);
            R compiledConstant = converter.apply(rawValue);

            // 运行时闭包：零逻辑，直接返回内存中的常量引用
            return context -> compiledConstant;
        }

        // 【动态分支】：将提取和转换逻辑推迟到运行时
        return context -> {
            T rawValue = provider.get(context);
            return converter.apply(rawValue);
        };
    }

    /**
     * 优化通配符类型的标量值
     * <p>
     * 用于处理 {@code Value<?>} 类型的提供者，例如在 {@code BetweenCriterion} 中
     * lowerProvider 和 upperProvider 都是 {@code Value<?>} 类型。
     *
     * @param provider  原始的 Value 提供者（通配符类型）
     * @param converter 类型转换逻辑
     * @param <R>       目标转换类型
     * @return 编译后的取值器
     */
    @SuppressWarnings("unchecked")
    public static <R> CompiledValue<R> optimizeRaw(Value<?> provider, Function<Object, R> converter) {
        return optimize((Value<Object>) provider, converter);
    }

    /**
     * 优化集合列表
     * <p>
     * 如果所有 Value 均为 {@link FixedValue}，则在编译期直接构建目标 Set。
     * 否则在运行时动态构建 Set。
     *
     * @param values 值提供者列表
     * @return 编译后的集合取值器
     */
    public static CompiledValue<Set<Object>> optimizeToSet(List<Value<?>> values) {
        boolean allFixed = true;
        for (Value<?> v : values) {
            if (!(v instanceof FixedValue)) {
                allFixed = false;
                break;
            }
        }

        if (allFixed) {
            // 【编译期组装】静态 Hash 集合
            Set<Object> staticSet = buildSet(values, null);
            return context -> staticSet;
        }

        // 【运行期组装】动态 Hash 集合
        return context -> buildSet(values, context);
    }

    /**
     * 构建期望比对的集合
     *
     * @param values  值提供者列表
     * @param context 匹配上下文（为 null 时表示编译期静态构建）
     * @return 提取出的期望值集合
     */
    private static Set<Object> buildSet(List<Value<?>> values, MatchContext context) {
        Set<Object> set = new HashSet<>();
        for (Value<?> provider : values) {
            Object v = provider.get(context);
            if (v instanceof Collection) {
                set.addAll((Collection<?>) v);
            } else if (v != null) {
                set.add(v);
            }
        }
        return set;
    }
}
