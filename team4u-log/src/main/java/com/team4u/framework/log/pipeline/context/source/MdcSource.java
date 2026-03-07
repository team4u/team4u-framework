package com.team4u.framework.log.pipeline.context.source;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.context.LogContextSource;
import org.slf4j.MDC;

/**
 * MDC (Mapped Diagnostic Context) 寻值源
 *
 * @author jay.wu
 */
public class MdcSource implements LogContextSource {

    @Override
    public Object getValue(LogEvent event, String key) {
        return MDC.get(key);
    }

    @Override
    public int priority() {
        return -90; // 低优先级，通常低于元数据和内部属性
    }
}
