package com.team4u.framework.message.core;

import com.team4u.framework.base.util.CollectionUtil;
import com.team4u.framework.message.core.interceptor.MessageInterceptor;
import com.team4u.framework.policy.core.OrderedPolicyChain;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 消息分发引擎
 * <p>
 * 消息框架的中枢调度系统。负责维护处理器注册表与拦截器链条，
 * 并基于策略匹配机制将消息准确投递至相应的业务处理器。
 * 支持同步或异步模式分发，并完整控制拦截器的生命周期链条。
 *
 * @author jay.wu
 */
@Slf4j
@SuppressWarnings({"unchecked", "rawtypes"})
public class MessageDispatcher {

    /**
     * 业务处理器策略链，支持基于消息特征的动态匹配
     */
    private final OrderedPolicyChain<Message<?>, MessageHandler<?>> handlerChain;

    /**
     * 拦截器策略链，支持全局切面逻辑扩展
     */
    private final OrderedPolicyChain<Message<?>, MessageInterceptor> interceptorChain;

    /**
     * 可选的异步分发线程池，若未指定则默认在调用线程同步分发
     */
    private final Executor executor;

    /**
     * 构建同步模式分发引擎
     */
    public MessageDispatcher() {
        this(null);
    }

    /**
     * 指定线程池构建异步模式分发引擎
     *
     * @param executor 指定的消息分发执行器
     */
    public MessageDispatcher(Executor executor) {
        this.executor = executor;
        this.handlerChain = new OrderedPolicyChain(MessageHandler.class);
        this.interceptorChain = new OrderedPolicyChain(MessageInterceptor.class);
    }

    /**
     * 注册业务处理器
     */
    public void addHandler(MessageHandler<?> handler) {
        handlerChain.register(handler);
        log.info("Registered MessageHandler: [{}] for PayloadType: [{}]",
                handler.getClass().getSimpleName(), handler.supportedPayloadType().getSimpleName());
    }

    /**
     * 移除业务处理器
     */
    public void removeHandler(MessageHandler<?> handler) {
        handlerChain.unregister(handler);
    }

    /**
     * 注册全局拦截器
     */
    public void addInterceptor(MessageInterceptor interceptor) {
        interceptorChain.register(interceptor);
        log.info("Registered MessageInterceptor: [{}]", interceptor.getClass().getSimpleName());
    }

    /**
     * 分发单条消息到注册的业务处理器
     *
     * @param message 待分发的消息信封
     * @return true 表示成功找到匹配项并开始投递，false 表示无匹配项
     */
    public boolean dispatch(Message<?> message) {
        if (message == null) {
            return false;
        }

        List<MessageHandler<?>> matchedHandlers = handlerChain.allMatches(message);
        if (CollectionUtil.isEmpty(matchedHandlers)) {
            if (log.isDebugEnabled()) {
                log.debug("No MessageHandler found for message type: [{}]", message.getMessageType());
            }
            return false;
        }

        List<MessageInterceptor> matchedInterceptors = interceptorChain.allMatches(message);

        Runnable dispatchTask = () -> executeWithInterceptors(message, matchedInterceptors, matchedHandlers);

        if (executor != null) {
            executor.execute(dispatchTask);
        } else {
            dispatchTask.run();
        }

        return true;
    }

    /**
     * 编排拦截器链与业务处理逻辑的执行流
     */
    private void executeWithInterceptors(Message<?> message,
                                         List<MessageInterceptor> interceptors,
                                         List<MessageHandler<?>> handlers) {
        Exception executionException = null;
        int interceptorIndex = -1;

        try {
            for (int i = 0; i < interceptors.size(); i++) {
                MessageInterceptor interceptor = interceptors.get(i);
                if (!interceptor.preHandle(message)) {
                    log.debug("Dispatch execution intercepted by preHandle of [{}]", interceptor.getClass().getSimpleName());
                    return;
                }
                interceptorIndex = i;
            }

            for (MessageHandler handler : handlers) {
                try {
                    handler.handle((Message) message);
                } catch (Exception ex) {
                    log.error("Handler execution failed for handler: [{}] messageId: [{}]",
                            handler.getClass().getSimpleName(), message.getId(), ex);
                    executionException = ex;
                }
            }

            if (executionException == null) {
                for (int i = interceptorIndex; i >= 0; i--) {
                    interceptors.get(i).postHandle(message);
                }
            }

        } catch (Exception ex) {
            executionException = ex;
            log.error("Unhandled exception during message dispatching for messageId: [{}]", message.getId(), ex);
        } finally {
            for (int i = interceptorIndex; i >= 0; i--) {
                try {
                    interceptors.get(i).afterCompletion(message, executionException);
                } catch (Exception afterEx) {
                    log.error("Exception thrown from afterCompletion of [{}]",
                            interceptors.get(i).getClass().getSimpleName(), afterEx);
                }
            }
        }
    }
}
