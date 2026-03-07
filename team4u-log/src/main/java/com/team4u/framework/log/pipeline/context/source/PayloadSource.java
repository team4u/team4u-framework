package com.team4u.framework.log.pipeline.context.source;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.pipeline.context.LogContextSource;

/**
 * 业务载荷寻值源
 * <p>
 * 负责从 LogEvent 的 payload Map 中提取数据。
 * 同时支持通过 "payload" 关键字获取完整的 Map。
 *
 * @author jay.wu
 */
public class PayloadSource implements LogContextSource {

    @Override
    public Object getValue(LogEvent event, String key) {
        if ("payload".equals(key)) {
            return event.getPayload();
        }

        return event.get(key);
    }

    @Override
    public int priority() {
        // 最高优先级，确保业务传入的 KV 优先于 MDC 和元数据
        return -200;
    }
}
