package com.team4u.framework.lease;

import com.team4u.framework.lease.api.TaskResult;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

public class TaskResultTest {

    @Test
    public void testSimpleDecisions() {
        TaskResult success = TaskResult.success();
        Assert.assertTrue(success.isSuccess());
        Assert.assertFalse(success.isRetry());
        Assert.assertFalse(success.isFailure());
        Assert.assertFalse(success.isCancel());
        Assert.assertNull(success.getRetryDelay());
        Assert.assertNull(success.getErrorMessage());

        TaskResult failure = TaskResult.failure();
        Assert.assertTrue(failure.isFailure());

        TaskResult cancel = TaskResult.cancel();
        Assert.assertTrue(cancel.isCancel());

        TaskResult retry = TaskResult.retryAfter(Duration.ofSeconds(3));
        Assert.assertTrue(retry.isRetry());
        Assert.assertEquals(Duration.ofSeconds(3), retry.getRetryDelay());
    }

    @Test
    public void testAdvancedWriteBack() {
        Map<String, String> attributes = Collections.singletonMap("attempt", "1");

        TaskResult success = TaskResult.success("done", attributes);
        Assert.assertTrue(success.isSuccess());
        Assert.assertEquals("done", success.getPayload());
        Assert.assertEquals(attributes, success.getAttributes());

        TaskResult failure = TaskResult.failure("bad input", "failed", attributes);
        Assert.assertTrue(failure.isFailure());
        Assert.assertEquals("bad input", failure.getErrorMessage());
        Assert.assertEquals("failed", failure.getPayload());
        Assert.assertEquals(attributes, failure.getAttributes());

        TaskResult cancel = TaskResult.cancel("user requested", "cancelled", attributes);
        Assert.assertTrue(cancel.isCancel());
        Assert.assertEquals("user requested", cancel.getErrorMessage());

        TaskResult retry = TaskResult.retryAfter(Duration.ofMillis(250), "retry later", "partial", attributes);
        Assert.assertTrue(retry.isRetry());
        Assert.assertEquals(Duration.ofMillis(250), retry.getRetryDelay());
        Assert.assertEquals("retry later", retry.getErrorMessage());
    }

    @Test
    public void testRetryDurationValidation() {
        assertRetryRejected(null);
        assertRetryRejected(Duration.ofMillis(-1));
        assertRetryRejected(Duration.ofSeconds(0, 1000));
        assertRetryRejected(Duration.ofSeconds(Long.MAX_VALUE));
    }

    @Test
    public void testDecisionFactoriesRejectWrongWriteBack() {
        try {
            TaskResult.success().withErrorMessage("invalid");
            Assert.fail("expected success with error to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("errorMessage"));
        }
    }

    private void assertRetryRejected(Duration delay) {
        try {
            TaskResult.retryAfter(delay);
            Assert.fail("expected invalid retry delay to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("retryDelay"));
        }
    }
}
