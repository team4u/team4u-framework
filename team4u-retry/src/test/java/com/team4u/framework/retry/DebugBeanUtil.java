package com.team4u.framework.retry;

import cn.hutool.core.bean.BeanUtil;

public class DebugBeanUtil {
    public static void main(String[] args) {
        RetryPolicy.RetryContext ctx = new RetryPolicy.RetryContext(1, 3, new RuntimeException("connection timeout"));
        System.out.println("attempt = " + BeanUtil.getProperty(ctx, "attempt"));
        System.out.println("message = " + BeanUtil.getProperty(ctx, "message"));
        System.out.println("cause.message = " + BeanUtil.getProperty(ctx, "cause.message"));
    }
}
