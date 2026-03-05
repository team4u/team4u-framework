package com.team4u.framework.retry.spring;

import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Configuration;

/**
 * 监听 Spring 容器销毁事件，触发重试引擎优雅停机。
 * <p>
 * 通过实现 {@link DisposableBean}，确保在 Spring 容器关闭时，
 * 能够调用全局管理器的停机逻辑，安全地关闭线程池。
 */
@Configuration
public class RetryLifecycleConfiguration implements DisposableBean {

    @Override
    public void destroy() throws Exception {
        // 代理给全局管理器进行安全的停机等待
        RetryExecutorManager.global().shutdown();
    }
}
