package com.team4u.framework.config.core.internal;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.convert.Convert;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.config.core.spi.ConfigBinder;

import java.util.HashMap;
import java.util.Map;

/**
 * 默认配置绑定器
 * <p>
 * 基于 Hutool 的 {@link BeanUtil} 和 {@link Convert} 实现配置绑定。
 * 支持松散绑定（驼峰、下划线、中划线互转）以及嵌套对象绑定。
 */
public class DefaultConfigBinder implements ConfigBinder {

    @SuppressWarnings("unchecked")
    @Override
    public <T> T bind(ConfigSnapshot snapshot, String prefix, Class<T> type) {
        if (snapshot == null || type == null) {
            return null;
        }

        // 1. 获取前缀下所有配置项（使用优化后的结构化视图）
        Object unflattenedValue = snapshot.getUnflattenedValue(prefix);

        if (unflattenedValue == null) {
            return null;
        }

        // 如果直接对应一个基本类型的值
        if (unflattenedValue instanceof String) {
            return Convert.convert(type, unflattenedValue);
        }

        // 2. 如果是嵌套 Map 结构
        Map<String, Object> unflattenedMap = (Map<String, Object>) unflattenedValue;

        // 3. 如果目标类型本身就是 Map，尝试直接转换
        if (Map.class.isAssignableFrom(type)) {
            return (T) unflattenedMap;
        }

        // 4. 将 Map 注入到 Java Bean 中，使用 Hutool 的 CopyOptions 支持松散绑定
        CopyOptions copyOptions = CopyOptions.create()
                .ignoreCase()
                .ignoreError(); // 忽略个别无法转换的字段错误

        // 由于旧版 hutool 的 ignoreCase 可能对中划线等特殊符号支持不完美，提前将 Map 中的分隔符去除
        Map<String, Object> processedMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : unflattenedMap.entrySet()) {
            // 统一调用核心的归一化算法
            String key = ConfigSnapshot.normalize(entry.getKey());
            processedMap.put(key, entry.getValue());
        }

        return BeanUtil.toBean(processedMap, type, copyOptions);
    }
}
