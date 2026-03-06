package com.team4u.framework.retry.spring;

import com.team4u.framework.retry.concurrent.RetryExecutorManager;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Configuration;

/**
 * 重试引擎生命周期配置
 * <p>
 * 监听 Spring 容器销毁事件，确保在应用关闭时能够优雅地停止重试引擎及相关线程池。
 */
@Configuration
public class RetryLifecycleConfiguration implements DisposableBean {

    @Override
    public void destroy() throws Exception {
        // 通知全局管理器执行停机逻辑，安全关闭调度线程池
        RetryExecutorManager.global().shutdown();
    }
}
