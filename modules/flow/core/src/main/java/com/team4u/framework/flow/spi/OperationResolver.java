package com.team4u.framework.flow.spi;

import java.lang.reflect.Proxy;
import java.util.Objects;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;

/**
 * 延迟绑定组件解析器 SPI（用于在流程编译/投影期将 {@code Class} 与可选 {@code qualifier} 解析为实际的 Spring Bean 或组件单例）。
 *
 * <p>核心职责与规范：
 * <ul>
 *   <li><b>编译期一次性解析</b>：在 {@link Local#compile} 或 Durable 编译时触发一次性解析并绑定，运行时直接调用已绑定的实例，避免运行期动态反射查找开销；</li>
 *   <li><b>代理实现类提取</b>：针对 Spring/JDK 动态代理对象，{@link #implementationClass(Object)} 会智能提取真实业务契约接口；</li>
 *   <li><b>默认拒绝器</b>：{@link #rejecting()} 提供无 IoC 容器环境下的默认拒绝占位实现。</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
@FunctionalInterface
public interface OperationResolver {

    /**
     * 根据契约类型与限定符解析目标组件实例（Operation/Policy/PersistentPolicy）。
     *
     * @param contract  契约 Class（接口或实现类），保证非 null
     * @param qualifier 可选的限定符名称（如 Spring Bean 名称），可为 null
     * @return 解析得到的组件实例，不能返回 null
     * @throws RuntimeException 当解析失败（如 Bean 不存在或冲突）时抛出
     */
    Object resolve(Class<?> contract, String qualifier);

    /**
     * 提取已解析实例的真实实现类型 Class（支持智能解包 JDK 动态代理）。
     *
     * @param resolved 已解析的组件实例，不能为 null
     * @return 实际的实现类型 Class
     * @throws NullPointerException 当 {@code resolved} 为 null 时抛出
     */
    default Class<?> implementationClass(Object resolved) {
        Objects.requireNonNull(resolved, "resolved must not be null");
        Class<?> type = resolved.getClass();
        if (Proxy.isProxyClass(type)) {
            for (Class<?> candidate : type.getInterfaces()) {
                if (candidate != Operation.class && candidate != Policy.class
                        && candidate != PersistentPolicy.class) {
                    return candidate;
                }
            }
        }
        return type;
    }

    /**
     * 获取全局默认组件解析器（优先通过 SPI 自动发现，若无实现则回退为 {@link #rejecting()} 拒绝解析器）。
     *
     * @return 全局默认解析器实例
     */
    static OperationResolver defaultResolver() {
        OperationResolver spi = com.team4u.framework.base.util.ServiceLoaderUtil.loadFirstAvailable(OperationResolver.class);
        return spi != null ? spi : rejecting();
    }

    /**
     * 创建默认的拒绝解析器（当流程中存在未解析的 class 绑定且未配置解析器时抛出异常）。
     *
     * @return 拒绝解析器实例
     */
    static OperationResolver rejecting() {
        return new OperationResolver() {
            @Override
            public Object resolve(Class<?> contract, String qualifier) {
                throw new IllegalStateException("No resolver for " + contract.getName()
                        + (qualifier == null ? "" : "[" + qualifier + "]"));
            }
        };
    }
}

