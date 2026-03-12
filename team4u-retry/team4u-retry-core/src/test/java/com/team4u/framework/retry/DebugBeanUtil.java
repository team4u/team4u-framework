package com.team4u.framework.retry;

import cn.hutool.core.bean.BeanUtil;
import com.team4u.framework.retry.api.RetryPolicy;

public class DebugBeanUtil {
    public static void main(String[] args) {
        RetryPolicy.RetryContext ctx = new RetryPolicy.RetryContext(1, 3, new RuntimeException("connection timeout"));
        System.out.println("retryCount = " + BeanUtil.getProperty(ctx, "retryCount"));
        System.out.println("maxRetries = " + BeanUtil.getProperty(ctx, "maxRetries"));
        System.out.println("message = " + BeanUtil.getProperty(ctx, "message"));
        System.out.println("cause.message = " + BeanUtil.getProperty(ctx, "cause.message"));
    }
}
