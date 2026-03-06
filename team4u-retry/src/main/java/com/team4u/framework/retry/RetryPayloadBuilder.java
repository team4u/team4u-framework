package com.team4u.framework.retry;

@FunctionalInterface
public interface RetryPayloadBuilder {

    String build(RetryPayloadContext context);
}
