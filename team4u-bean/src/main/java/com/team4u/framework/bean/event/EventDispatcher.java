package com.team4u.framework.bean.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 轻量级内部事件分发器
 * <p>
 * 提供核心库内部的状态变更通知（如 {@link BeanInitializedEvent}）。
 * 设计目标是轻量级且无外部依赖。在复杂的集成环境中，可通过 {@code publish} 逻辑
 * 将事件桥接到 Spring Event 或其他成熟的消息总线。
 *
 * @author jay.wu
 */
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    /**
     * 同步发布指定事件对象。
     * <p>
     * 目前主要用于打印调试日志。可在此处扩展自定义监听器回调或集成第三方总线。
     *
     * @param event 事件对象（非 null）
     */
    public static void publish(Object event) {
        if (log.isDebugEnabled()) {
            log.debug("Publishing internal event: {}", event);
        }
        // TODO: 可在此集成自定义的 Observer 模式实现或桥接到 Spring 上下文
    }
}
