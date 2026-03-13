package com.team4u.framework.message.core.interceptor;

import com.team4u.framework.base.util.DateUtil;
import com.team4u.framework.base.util.DateUtil.TimeInterval;
import com.team4u.framework.message.core.Message;
import lombok.extern.slf4j.Slf4j;

/**
 * 耗时统计日志拦截器
 * <p>
 * 提供开箱即用的处理监控能力。通过拦截器生命周期记录消息处理全过程的状态信息。
 * 利用 ThreadLocal 计算起止时间差，输出结构化的日志以协助生产环境下的链路排查。
 *
 * @author jay.wu
 */
@Slf4j
public class LoggingMessageInterceptor implements MessageInterceptor {

    /**
     * 记录消息起止时间的线程局部变量，用于计算耗时
     */
    private static final ThreadLocal<TimeInterval> TIMER_LOCAL = new ThreadLocal<>();

    @Override
    public boolean preHandle(Message<?> message) {
        TIMER_LOCAL.set(DateUtil.timer());
        log.info("Message Dispatch | START | ID: [{}] | Type: [{}]",
                message.getId(), message.getMessageType());
        return true;
    }

    @Override
    public void postHandle(Message<?> message) {
        // 在业务处理器顺利完成后执行回调
    }

    @Override
    public void afterCompletion(Message<?> message, Exception ex) {
        TimeInterval timer = TIMER_LOCAL.get();
        long cost = timer != null ? timer.interval() : -1;
        TIMER_LOCAL.remove();

        if (ex == null) {
            log.info("Message Dispatch | SUCCESS | ID: [{}] | Cost: [{}ms]",
                    message.getId(), cost);
        } else {
            log.error("Message Dispatch | FAILED | ID: [{}] | Cost: [{}ms] | Error: [{}]",
                    message.getId(), cost, ex.getMessage());
        }
    }
}
