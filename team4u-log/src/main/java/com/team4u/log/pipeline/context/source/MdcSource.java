package com.team4u.log.pipeline.context.source;

import com.team4u.log.core.LogEvent;
import com.team4u.log.pipeline.context.LogContextSource;
import org.slf4j.MDC;

import java.util.Map;

/**
 * MDC (Mapped Diagnostic Context) 寻值源
 * <p>
 * 支持两种访问方式：
 * 1. 精确前缀访问：mdc.key (高性能，只拉取单个 key)
 * 2. 嵌套全量访问：mdc (低性能，返回整个 MDC Map 拷贝)
 *
 * @author team4u
 */
public class MdcSource implements LogContextSource {

    private static final String MDC_PREFIX = "mdc.";
    private static final String MDC_KEY = "mdc";

    @Override
    public Object getValue(LogEvent event, String key) {
        // 1. 高性能模式：只取指定的 MDC Key，避免全量拷贝
        if (key.startsWith(MDC_PREFIX)) {
            return MDC.get(key.substring(MDC_PREFIX.length()));
        }

        // 2. 兼容模式：若 key 就是 "mdc"，则返回全量拷贝
        if (MDC_KEY.equals(key)) {
            return MDC.getCopyOfContextMap();
        }

        return null;
    }

    @Override
    public int priority() {
        return -90; // 与旧版的 FullMdcContributor 保持一致
    }
}
