package com.team4u.framework.lease.spi;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

public class AdminCompletionCommandTest {

    @Test
    public void testValidatesAndExposesCompletion() {
        LeaseCompletion completion = LeaseCompletion.cancelled(
                "user requested", "payload", Collections.singletonMap("traceId", "trace-1"));
        AdminCompletionCommand command = AdminCompletionCommand.of("orders", "task-1", completion);

        Assert.assertEquals("orders", command.getQueue());
        Assert.assertEquals("task-1", command.getTaskId());
        Assert.assertSame(completion, command.getCompletion());
    }

    @Test
    public void testRejectsInvalidValues() {
        assertRejected(null, "task-1", LeaseCompletion.succeeded(null, null), "queue");
        assertRejected(" ", "task-1", LeaseCompletion.succeeded(null, null), "queue");
        assertRejected("orders", null, LeaseCompletion.succeeded(null, null), "taskId");
        assertRejected("orders", "", LeaseCompletion.succeeded(null, null), "taskId");
        assertRejected("orders", "task-1", null, "completion");
    }

    private void assertRejected(String queue, String taskId, LeaseCompletion completion, String field) {
        try {
            AdminCompletionCommand.of(queue, taskId, completion);
            Assert.fail("expected " + field + " to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains(field));
        }
    }
}
