package com.team4u.framework.base.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 健壮的服务加载工具类
 * <p>
 * 基于 Java 原生 {@link ServiceLoader} 封装，增加了错误容忍和详细的调试日志。
 * 即使某些 SPI 实现类因依赖缺失或初始化异常而无法加载，也不会中断其他合法实现的加载。
 * </p>
 *
 * @author jay.wu
 */
public class ServiceLoaderUtil {

    private static final Logger log = LoggerFactory.getLogger(ServiceLoaderUtil.class);

    /**
     * 加载首个可用的服务实例
     *
     * @param type 服务接口类型
     * @param <T>  服务接口泛型
     * @return 首个可用的服务实例，若未找到或加载失败则返回 {@code null}
     */
    public static <T> T loadFirstAvailable(Class<T> type) {
        List<T> list = loadAvailableList(type);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 加载所有可用的服务实例列表
     * <p>
     * 该方法具备容错性：若某个实现类加载失败（如 {@link ServiceConfigurationError}），
     * 会记录警告日志并继续尝试加载后续实现。
     * </p>
     *
     * @param type 服务接口类型
     * @param <T>  服务接口泛型
     * @return 已成功实例化的服务列表
     */
    public static <T> List<T> loadAvailableList(Class<T> type) {
        log.debug("Loading service list from SPI: {}", type.getName());

        ServiceLoader<T> loader = ServiceLoader.load(type);
        Iterator<T> iterator = loader.iterator();
        List<T> result = new ArrayList<>();

        while (iterator.hasNext()) {
            try {
                result.add(iterator.next());
            } catch (ServiceConfigurationError e) {
                log.warn("Failed to load service implementation for: {}, error: {}", type.getName(), e.getMessage());
                if (log.isTraceEnabled()) {
                    log.trace(e.getMessage(), e);
                }
            }
        }

        log.info("Finished loading service list from SPI: {}, count: {}, implementations: {}",
                type.getName(),
                result.size(),
                result.stream()
                        .map(it -> it.getClass().getName())
                        .collect(Collectors.toList()));
        return result;
    }

    /**
     * 获取指定类型的 {@link ServiceLoader}
     *
     * @param type 服务接口类型
     * @param <T>  服务接口泛型
     * @return 服务加载器实例
     */
    public static <T> ServiceLoader<T> load(Class<T> type) {
        return ServiceLoader.load(type);
    }
}
