package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.Leases;
import com.team4u.framework.lease.api.Task;
import com.team4u.framework.lease.api.TaskQueue;
import com.team4u.framework.lease.api.TaskResult;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.runtime.TaskWorker;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

public class InMemoryLeaseEndToEndTest {

    private static final long TIMEOUT_MILLIS = 5_000L;

    @Test
    public void taskQueueWorkerCompletesSubmittedTaskWithoutConfiguredWorkerId() throws Exception {
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend();
        TaskQueue queue = Leases.queue(backend, "orders");
        AtomicReference<String> actualPayload = new AtomicReference<String>();

        queue.submit(Task.of("email.send", "{\"message\":\"welcome\"}"));

        TaskWorker worker = queue.worker()
                .handle("email.send", context -> {
                    actualPayload.set(context.getPayload());
                    return TaskResult.success("done", Collections.singletonMap("traceId", "T-1"));
                })
                .lease(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(20))
                .heartbeatEnabled(false)
                .build().start();
        try {
            TaskSnapshot completed = waitForSuccess(queue, "email.send");
            Assert.assertEquals("{\"message\":\"welcome\"}", actualPayload.get());
            Assert.assertEquals(TaskStatus.SUCCEEDED, completed.getStatus());
            Assert.assertEquals("done", completed.getPayload());
            Assert.assertEquals("T-1", completed.getAttributes().get("traceId"));
            Assert.assertNull(completed.getWorkerId());
        } finally {
            worker.shutdown();
        }
    }

    private TaskSnapshot waitForSuccess(TaskQueue queue, String type) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000L;
        TaskSnapshot last = null;
        while (System.nanoTime() < deadline) {
            for (TaskSnapshot snapshot : queue.list(com.team4u.framework.lease.api.TaskQuery
                    .builder().type(type).build()).getTasks()) {
                last = snapshot;
                if (snapshot.getStatus() == TaskStatus.SUCCEEDED) {
                    return snapshot;
                }
            }
            Thread.sleep(20L);
        }
        Assert.fail("task did not succeed within timeout, last status="
                + (last == null ? "none" : last.getStatus()));
        throw new AssertionError("unreachable");
    }
}
