package com.team4u.framework.lease.spi;

public final class AdminCompletionCommand {

    private final String queue;
    private final String taskId;
    private final LeaseCompletion completion;

    private AdminCompletionCommand(String queue, String taskId, LeaseCompletion completion) {
        this.queue = LeaseValues.requireText(queue, "queue");
        this.taskId = LeaseValues.requireText(taskId, "taskId");
        this.completion = requireCompletion(completion);
    }

    public static AdminCompletionCommand of(String queue, String taskId, LeaseCompletion completion) {
        return new AdminCompletionCommand(queue, taskId, completion);
    }

    public String getQueue() {
        return queue;
    }

    public String getTaskId() {
        return taskId;
    }

    public LeaseCompletion getCompletion() {
        return completion;
    }

    private static LeaseCompletion requireCompletion(LeaseCompletion completion) {
        if (completion == null) {
            throw new IllegalArgumentException("completion must not be null");
        }
        return completion;
    }
}
