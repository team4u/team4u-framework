package com.team4u.framework.bean.event;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;

/**
 * 内部简易事件分发器
 *
 * @author team4u
 */
public class EventDispatcher {

    private static final Log log = LogFactory.get();

    /**
     * 发布事件
     * <p>
     * 实际项目中可桥接到 Spring Event 或其他消息总线
     */
    public static void publish(Object event) {
        if (log.isDebugEnabled()) {
            log.debug("Publishing event: {}", event);
        }
        // 此处可扩展订阅者逻辑
    }
}
