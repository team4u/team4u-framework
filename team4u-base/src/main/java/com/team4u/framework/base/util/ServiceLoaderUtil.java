package com.team4u.framework.base.util;

import cn.hutool.log.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.stream.Collectors;

/**
 * SPI机制中的服务加载工具类
 *
 * @author team4u
 * @see cn.hutool.core.util.ServiceLoaderUtil
 */
public class ServiceLoaderUtil extends cn.hutool.core.util.ServiceLoaderUtil {

    private static final Log log = Log.get();

    /**
     * 加载可用的服务列表
     *
     * @param type 服务接口
     * @param <T>  接口类型
     * @return 可用的服务列表
     */
    public static <T> List<T> loadAvailableList(Class<T> type) {
        log.debug("Loading service list from SPI: {}", type.getName());

        Iterator<T> iterator = load(type).iterator();
        List<T> result = new ArrayList<>();

        while (iterator.hasNext()) {
            try {
                result.add(iterator.next());
            } catch (ServiceConfigurationError e) {
                log.warn("Failed to load service implementation for: {}, error: {}", type.getName(), e.getMessage());
                if (log.isTraceEnabled()) {
                    log.trace(e);
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
}
