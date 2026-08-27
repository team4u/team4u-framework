package com.team4u.framework.lease.api;

import com.team4u.framework.lease.runtime.TaskWorker;

import java.time.Duration;
import java.util.Optional;

public interface TaskQueue {

    String name();

    Submission submit(Task task);

    Optional<TaskSnapshot> get(String taskId);

    Optional<TaskSnapshot> get(String taskType, String dedupKey);

    TaskPage list(TaskQuery query);

    TaskOperationResult complete(String taskId, TaskResult result);

    TaskOperationResult cancel(String taskId, String reason);

    TaskOperationResult reschedule(String taskId, Duration delay);

    TaskOperationResult retry(String taskId, Duration delay);

    TaskOperationResult update(TaskPatch patch);

    TaskOperationResult updateAndReschedule(TaskPatch patch, Duration delay);

    TaskWorker.Builder worker();
}
