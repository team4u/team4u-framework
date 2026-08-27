package com.team4u.framework.lease.api;

@FunctionalInterface
public interface TaskHandler {

    TaskResult handle(TaskContext context) throws Exception;
}
