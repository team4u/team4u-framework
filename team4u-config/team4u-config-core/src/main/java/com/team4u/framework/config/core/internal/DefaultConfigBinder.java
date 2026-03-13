package com.team4u.framework.config.core.internal;

import com.team4u.framework.base.convert.ConvertUtil;
import com.team4u.framework.base.util.BeanUtil;
import com.team4u.framework.base.util.CopyOptions;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.spi.ConfigBinder;

import java.util.HashMap;
import java.util.Map;

/**
 * 默认配置绑定器实现
 * <p>
 * 基于 Hutool 的 {@link BeanUtil} 和 {@link ConvertUtil} 实现配置到 Java 对象的映射。
 * 支持松散绑定（自动处理驼峰、下划线、中划线等命名风格差异）以及深层嵌套对象的绑定。
 * </p>
 */
public class DefaultConfigBinder implements ConfigBinder {

    @SuppressWarnings("unchecked")
    @Override
    public <T> T bind(ConfigSnapshot snapshot, String prefix, Class<T> type) {
        if (snapshot == null || type == null) {
            return null;
        }

        // 获取指定前缀下的结构化视图数据
        Object unflattenedValue = snapshot.getUnflattenedValue(prefix);

        if (unflattenedValue == null) {
            return null;
        }

        // 如果获取到的值直接对应一个基础类型或字符串，则执行简单类型转换
        if (unflattenedValue instanceof String) {
            return ConvertUtil.convert(type, unflattenedValue);
        }

        // 处理嵌套的 Map 结构映射
        Map<String, Object> unflattenedMap = (Map<String, Object>) unflattenedValue;

        // 如果目标类型本身就是 Map 接口或其实现类，则直接返回结构化视图数据
        if (Map.class.isAssignableFrom(type)) {
            return (T) unflattenedMap;
        }

        // 将 Map 数据注入到 Java Bean 中，利用 Hutool 的 CopyOptions 增强绑定灵活性
        CopyOptions copyOptions = CopyOptions.create()
                .ignoreCase()
                .ignoreError();

        // 针对某些特殊命名风格的兼容性预处理：将 Map 中的键统一进行归一化处理
        Map<String, Object> processedMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : unflattenedMap.entrySet()) {
            String key = ConfigSnapshot.normalize(entry.getKey());
            processedMap.put(key, entry.getValue());
        }

        return BeanUtil.toBean(processedMap, type, copyOptions);
    }
}