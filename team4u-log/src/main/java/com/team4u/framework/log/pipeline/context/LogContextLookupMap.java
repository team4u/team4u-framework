package com.team4u.framework.log.pipeline.context;

import com.team4u.framework.log.core.LogEvent;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 日志上下文延迟寻值 Map (高性能代理模型)
 * <p>
 * 核心设计思路：按需拉取数据。该 Map 本身不持有任何键值对拷贝，
 * 只有当表达式引擎调用其 get(key) 时，才会依次询问所有的 {@link LogContextSource}。
 * 它不是一个支持完整遍历语义的通用 Map，而是一个 lookup-only 适配器：
 * 允许通过 get/containsKey 取值，但不承诺 entrySet/size 等遍历相关行为。
 *
 * @author jay.wu
 */
public class LogContextLookupMap extends AbstractMap<String, Object> {

    private final LogEvent event;
    private final List<LogContextSource> sources;

    public LogContextLookupMap(LogEvent event, List<LogContextSource> sources) {
        this.event = event;
        this.sources = sources;
    }

    @Override
    public Object get(Object keyObj) {
        if (!(keyObj instanceof String)) {
            return null;
        }

        String key = (String) keyObj;

        // 轮询各个寻值源策略 (如 PayloadSource, BasicMetadataSource, MdcSource 等)
        for (LogContextSource source : sources) {
            Object value = source.getValue(event, key);
            if (value != null) {
                return value;
            }
        }

        return null;
    }

    /**
     * 对于染色和匹配规则引擎，通常只用到 get 方法。
     * 保持 entrySet 为空，避免无谓的遍历或生成临时对象。
     */
    @Override
    public Set<Entry<String, Object>> entrySet() {
        return Collections.emptySet();
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }
}
