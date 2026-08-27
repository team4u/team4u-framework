package com.team4u.framework.lease.api;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.LeaseHandle;
import com.team4u.framework.lease.spi.SubmitResult;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskModelsTest {

    @Test
    public void testSubmissionKeepsOnlyResultContract() {
        TaskSnapshot snapshot = pendingSnapshot();
        Submission submission = Submission.of("task-1", true, snapshot);

        Assert.assertEquals("task-1", submission.getTaskId());
        Assert.assertTrue(submission.isCreated());
        Assert.assertSame(snapshot, submission.getTask());

        assertRejected(new Runnable() {
            @Override
            public void run() {
                Submission.of("other-task", true, pendingSnapshot());
            }
        }, "taskId");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                Submission.of("task-1", true, null);
            }
        }, "snapshot");
    }

    @Test
    public void testSubmitResultRequiresConsistentTaskId() {
        assertRejected(new Runnable() {
            @Override
            public void run() {
                SubmitResult.of("other-task", true, pendingSnapshot());
            }
        }, "taskId");
    }

    @Test
    public void testLeaseGrantRequiresRunningTaskForSameWorker() {
        LeaseHandle handle = LeaseHandle.of("task-1", "worker-1", "lease-1");
        Assert.assertSame(handle, LeaseGrant.of(handle, runningSnapshot("worker-1")).getHandle());

        assertRejected(new Runnable() {
            @Override
            public void run() {
                LeaseGrant.of(handle, pendingSnapshot());
            }
        }, "status");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                LeaseGrant.of(handle, runningSnapshot("other-worker"));
            }
        }, "workerId");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                LeaseGrant.of(handle, renamedSnapshot());
            }
        }, "taskId");
    }

    @Test
    public void testSnapshotValidatesNumbersAndLeaseOwnership() {
        Assert.assertEquals(3, runningSnapshot("worker-1").getPriority());
        Assert.assertEquals(1, runningSnapshot("worker-1").getAttemptCount());

        assertRejected(new Runnable() {
            @Override
            public void run() {
                snapshotBuilder().priority(-1).build();
            }
        }, "priority");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                snapshotBuilder().attemptCount(-1).build();
            }
        }, "attemptCount");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                snapshotBuilder().status(TaskStatus.RUNNING).build();
            }
        }, "workerId");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                snapshotBuilder().status(TaskStatus.RUNNING)
                        .workerId("worker-1").build();
            }
        }, "leaseExpiresAt");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                runningBuilder("worker-1").status(TaskStatus.PENDING).build();
            }
        }, "workerId");
    }

    @Test
    public void testPageDefensivelyCopiesTasks() {
        List<TaskSnapshot> source = new ArrayList<TaskSnapshot>();
        source.add(pendingSnapshot());
        TaskPage page = TaskPage.of(source, 0, 1, 1);
        source.clear();

        Assert.assertEquals(1, page.getTasks().size());
    }

    @Test
    public void testPatchAttributesUseNullAndEmptyMapDistinctly() {
        TaskPatch unchanged = TaskPatch.builder().taskId("task-1").payload("value").build();
        Assert.assertFalse(unchanged.hasAttributes());

        TaskPatch clear = TaskPatch.builder().taskId("task-1")
                .attributes(Collections.<String, String>emptyMap()).build();
        Assert.assertTrue(clear.hasAttributes());
        Assert.assertTrue(clear.getAttributes().isEmpty());

        assertRejected(new Runnable() {
            @Override
            public void run() {
                TaskPatch.builder().taskId("task-1").attributes(null);
            }
        }, "attributes");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                Map<String, String> invalid = new LinkedHashMap<String, String>();
                invalid.put(" ", "value");
                TaskPatch.builder().taskId("task-1").attributes(invalid);
            }
        }, "attribute");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                Map<String, String> invalid = new LinkedHashMap<String, String>();
                invalid.put("key", null);
                TaskPatch.builder().taskId("task-1").attributes(invalid);
            }
        }, "attribute");
    }

    @Test
    public void testResultAttributesUseNullAndEmptyMapDistinctly() throws Exception {
        TaskResult unchanged = TaskResult.success();
        Assert.assertFalse(unchanged.hasAttributes());
        TaskResult changed = unchanged.withAttributes(
                Collections.singletonMap("attempt", "1"));
        Assert.assertTrue(changed.hasAttributes());
        Assert.assertEquals(Collections.singletonMap("attempt", "1"), changed.getAttributes());

        TaskResult advanced = TaskResult.success("done",
                Collections.<String, String>emptyMap());
        Assert.assertTrue(advanced.hasAttributes());
        Assert.assertTrue(advanced.getAttributes().isEmpty());

        assertRejected(new Runnable() {
            @Override
            public void run() {
                TaskResult.success("done", null);
            }
        }, "attributes");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                TaskResult.failure("error", "payload", null);
            }
        }, "attributes");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                TaskResult.cancel("error", "payload", null);
            }
        }, "attributes");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                TaskResult.retryAfter(Duration.ZERO, "error", "payload", null);
            }
        }, "attributes");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                TaskResult.success().withAttributes(null);
            }
        }, "attributes");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                Map<String, String> invalid = new LinkedHashMap<String, String>();
                invalid.put(" ", "value");
                TaskResult.success("done", invalid);
            }
        }, "attribute");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                Map<String, String> invalid = new LinkedHashMap<String, String>();
                invalid.put("key", null);
                TaskResult.success("done", invalid);
            }
        }, "attribute");
    }

    @Test
    public void testResultDoesNotExposeUnusedRetryDelayMutation() throws Exception {
        for (java.lang.reflect.Method method : TaskResult.class.getMethods()) {
            Assert.assertNotEquals("withRetryDelay", method.getName());
        }
    }

    @Test
    public void testQueryRejectsBlankOptionalText() {
        TaskQuery valid = TaskQuery.builder().type("email.send").workerId("worker-1").build();
        Assert.assertEquals("email.send", valid.getType());
        Assert.assertEquals("worker-1", valid.getWorkerId());

        assertRejected(new Runnable() {
            @Override
            public void run() {
                TaskQuery.builder().type(" ").build();
            }
        }, "type");
        assertRejected(new Runnable() {
            @Override
            public void run() {
                TaskQuery.builder().workerId(" ").build();
            }
        }, "workerId");
    }

    @Test
    public void testDurationsRejectNegativeOverflowAndSubMilliPrecision() {
        assertInvalidDuration(new DurationFactory() {
            @Override
            public void apply(Duration duration) {
                Task.of("email.send", "{}").delay(duration);
            }
        });
        assertInvalidDuration(new DurationFactory() {
            @Override
            public void apply(Duration duration) {
                TaskResult.retryAfter(duration);
            }
        });
    }

    @Test
    public void testQueueDurationEntrancesRejectInvalidDurations() {
        final com.team4u.framework.lease.runtime.DefaultTaskQueue queue =
                new com.team4u.framework.lease.runtime.DefaultTaskQueue(
                        new RecordingBackend(), "orders");
        assertInvalidDuration(new DurationFactory() {
            @Override
            public void apply(Duration duration) {
                queue.reschedule("task-1", duration);
            }
        });
        assertInvalidDuration(new DurationFactory() {
            @Override
            public void apply(Duration duration) {
                queue.retry("task-1", duration);
            }
        });
        assertInvalidDuration(new DurationFactory() {
            @Override
            public void apply(Duration duration) {
                queue.updateAndReschedule(TaskPatch.builder().taskId("task-1").build(), duration);
            }
        });
    }

    @Test
    public void testQueueConstructorRejectsInvalidConstruction() {
        assertQueueRejected(null, "orders", "backend");
        assertQueueRejected(new RecordingBackend(), null, "queueName");
        assertQueueRejected(new RecordingBackend(), " ", "queueName");
    }

    private static void assertQueueRejected(RecordingBackend backend, String queueName,
                                              String messagePart) {
        try {
            new com.team4u.framework.lease.runtime.DefaultTaskQueue(backend, queueName);
            Assert.fail("expected invalid queue construction to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    private static void assertInvalidDuration(DurationFactory factory) {
        assertDurationRejected(factory, Duration.ofMillis(-1));
        assertDurationRejected(factory, Duration.ofSeconds(Long.MAX_VALUE));
        assertDurationRejected(factory, Duration.ofSeconds(0, 1000));
    }

    private static void assertDurationRejected(DurationFactory factory, Duration duration) {
        try {
            factory.apply(duration);
            Assert.fail("expected invalid duration to be rejected: " + duration);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertRejected(Runnable runnable, String messagePart) {
        try {
            runnable.run();
            Assert.fail("expected IllegalArgumentException containing " + messagePart);
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }

    private interface DurationFactory {
        void apply(Duration duration);
    }

    private static TaskSnapshot.Builder snapshotBuilder() {
        return TaskSnapshot.builder().taskId("task-1")
                .queue("orders")
                .type("email.send")
                .payload("{}")
                .status(TaskStatus.PENDING)
                .priority(3)
                .attemptCount(1)
                .createdAt(Instant.EPOCH)
                .visibleAt(Instant.EPOCH);
    }

    private static final class RecordingBackend
            implements com.team4u.framework.lease.spi.LeaseBackend {

        @Override
        public com.team4u.framework.lease.spi.SubmitResult submit(
                com.team4u.framework.lease.spi.SubmitCommand command) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public com.team4u.framework.lease.spi.LeaseGrant acquire(
                com.team4u.framework.lease.spi.AcquireCommand command) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public com.team4u.framework.lease.spi.RuntimeResult heartbeat(
                com.team4u.framework.lease.spi.LeaseHandle handle, long extendMillis) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public com.team4u.framework.lease.spi.RuntimeResult close(
                com.team4u.framework.lease.spi.LeaseHandle handle,
                com.team4u.framework.lease.spi.LeaseCompletion completion) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public com.team4u.framework.lease.spi.RuntimeResult release(
                com.team4u.framework.lease.spi.LeaseHandle handle,
                com.team4u.framework.lease.spi.LeaseRetry retry) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public java.util.Optional<TaskSnapshot> get(String queue, String taskId) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public java.util.Optional<TaskSnapshot> getByDeduplicationKey(
                String queue, String taskType, String key) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public TaskPage list(String queue, TaskQuery query) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public com.team4u.framework.lease.spi.AdminResult complete(
                com.team4u.framework.lease.spi.AdminCompletionCommand command) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public com.team4u.framework.lease.spi.AdminResult reschedule(
                com.team4u.framework.lease.spi.RescheduleCommand command) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public com.team4u.framework.lease.spi.AdminResult retry(
                com.team4u.framework.lease.spi.RetryCommand command) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public com.team4u.framework.lease.spi.AdminResult update(
                com.team4u.framework.lease.spi.UpdateCommand command) {
            throw new UnsupportedOperationException("not expected");
        }

        @Override
        public com.team4u.framework.lease.spi.AdminResult updateAndReschedule(
                com.team4u.framework.lease.spi.UpdateCommand command) {
            throw new UnsupportedOperationException("not expected");
        }
    }

    private static TaskSnapshot.Builder runningBuilder(String workerId) {
        return snapshotBuilder().status(TaskStatus.RUNNING)
                .workerId(workerId)
                .leaseExpiresAt(Instant.EPOCH.plusMillis(1000));
    }

    private static TaskSnapshot pendingSnapshot() {
        return snapshotBuilder().build();
    }

    private static TaskSnapshot runningSnapshot(String workerId) {
        return runningBuilder(workerId).build();
    }

    private static TaskSnapshot renamedSnapshot() {
        return runningBuilder("worker-1").taskId("other-task").build();
    }
}
