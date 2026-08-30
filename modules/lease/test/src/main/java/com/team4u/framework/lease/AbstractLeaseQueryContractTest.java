package com.team4u.framework.lease;

import com.team4u.framework.lease.api.TaskPage;
import com.team4u.framework.lease.api.TaskQuery;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.spi.LeaseBackend;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class AbstractLeaseQueryContractTest extends AbstractLeaseContractSupport {

    @Test
    public void testGetIsScopedByQueue() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(taskId, snapshot.getTaskId());
        Assert.assertEquals(DEFAULT_QUEUE, snapshot.getQueue());
        Assert.assertEquals(PAY_TASK_TYPE, snapshot.getType());
        Assert.assertEquals("payload", snapshot.getPayload());
        Assert.assertFalse(backend.get("invoices", taskId).isPresent());
    }

    @Test
    public void testGetReturnsPendingSnapshot() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload", null,
                LONG_LEASE_MILLIS, Collections.singletonMap("traceId", "T-1"));

        TaskSnapshot snapshot = snapshot(backend, DEFAULT_QUEUE, taskId);
        Assert.assertEquals(TaskStatus.PENDING, snapshot.getStatus());
        Assert.assertNull(snapshot.getWorkerId());
        Assert.assertNull(snapshot.getLeaseExpiresAt());
        Assert.assertEquals(0, snapshot.getAttemptCount());
        Assert.assertNotNull(snapshot.getCreatedAt());
        Assert.assertNotNull(snapshot.getVisibleAt());
        Assert.assertEquals("T-1", snapshot.getAttributes().get("traceId"));
    }

    @Test
    public void testDeduplicationLookupRequiresQueueTypeAndKey() {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "payload",
                "dedup-1", 0L, Collections.<String, String>emptyMap());

        Assert.assertEquals(taskId, backend.getByDeduplicationKey(
                DEFAULT_QUEUE, PAY_TASK_TYPE, "dedup-1").get().getTaskId());
        Assert.assertFalse(backend.getByDeduplicationKey(
                "invoices", PAY_TASK_TYPE, "dedup-1").isPresent());
        Assert.assertFalse(backend.getByDeduplicationKey(
                DEFAULT_QUEUE, MAIL_TASK_TYPE, "dedup-1").isPresent());
        Assert.assertFalse(backend.getByDeduplicationKey(
                DEFAULT_QUEUE, PAY_TASK_TYPE, "missing").isPresent());
    }

    @Test
    public void testListIsScopedByQueue() {
        LeaseBackend backend = createBackend();
        String ordersTaskId = submit(backend, PAY_TASK_TYPE, "orders");
        submit(backend, "invoices", PAY_TASK_TYPE, "invoices");

        List<String> taskIds = taskIds(backend.list(DEFAULT_QUEUE, TaskQuery.builder().build()));
        Assert.assertEquals(Collections.singletonList(ordersTaskId), taskIds);
    }

    @Test
    public void testListFiltersByType() {
        LeaseBackend backend = createBackend();
        String payTaskId = submit(backend, PAY_TASK_TYPE, "payload");
        submit(backend, MAIL_TASK_TYPE, "payload");

        Assert.assertEquals(Collections.singletonList(payTaskId),
                taskIds(backend.list(DEFAULT_QUEUE, TaskQuery.builder().type(PAY_TASK_TYPE).build())));
    }

    @Test
    public void testListFiltersByPendingStatus() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String pendingTaskId = submit(backend, DEFAULT_QUEUE, PAY_TASK_TYPE, "pending", null,
                LONG_LEASE_MILLIS, Collections.<String, String>emptyMap());
        String visibleTaskId = submit(backend, PAY_TASK_TYPE, "visible");
        acquire(backend, PAY_TASK_TYPE, WORKER_A, LONG_LEASE_MILLIS);

        Set<String> expected = new HashSet<String>(Arrays.asList(pendingTaskId));
        Assert.assertEquals(expected, new HashSet<String>(taskIds(backend.list(DEFAULT_QUEUE,
                TaskQuery.builder().status(TaskStatus.PENDING).build()))));
        Assert.assertFalse(taskIds(backend.list(DEFAULT_QUEUE,
                TaskQuery.builder().status(TaskStatus.PENDING).build())).contains(visibleTaskId));
    }

    @Test
    public void testListFiltersByRunningStatus() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String runningTaskId = submit(backend, PAY_TASK_TYPE, "running");
        assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LONG_LEASE_MILLIS),
                runningTaskId, WORKER_A);

        Assert.assertEquals(Collections.singletonList(runningTaskId),
                taskIds(backend.list(DEFAULT_QUEUE, TaskQuery.builder().status(TaskStatus.RUNNING).build())));
    }

    @Test
    public void testListFiltersByWorkerForRunningTask() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "payload");
        assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LONG_LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(Collections.singletonList(taskId), taskIds(backend.list(DEFAULT_QUEUE,
                TaskQuery.builder().workerId(WORKER_A).build())));
        Assert.assertTrue(taskIds(backend.list(DEFAULT_QUEUE,
                TaskQuery.builder().workerId(WORKER_B).build())).isEmpty());
    }

    @Test
    public void testListPaginatesTasks() {
        LeaseBackend backend = createBackend();
        String first = submit(backend, PAY_TASK_TYPE, "first");
        String second = submit(backend, PAY_TASK_TYPE, "second");
        String third = submit(backend, PAY_TASK_TYPE, "third");

        TaskPage firstPage = backend.list(DEFAULT_QUEUE, TaskQuery.builder()
                .page(0).pageSize(2).build());
        TaskPage secondPage = backend.list(DEFAULT_QUEUE, TaskQuery.builder()
                .page(1).pageSize(2).build());
        TaskPage emptyPage = backend.list(DEFAULT_QUEUE, TaskQuery.builder()
                .page(2).pageSize(2).build());

        Assert.assertEquals(2, firstPage.getTasks().size());
        Assert.assertEquals(3L, firstPage.getTotal());
        Assert.assertEquals(1, secondPage.getTasks().size());
        Assert.assertEquals(3L, secondPage.getTotal());
        Assert.assertTrue(emptyPage.getTasks().isEmpty());
        Assert.assertEquals(0, emptyPage.getTasks().size());
        Assert.assertEquals(3L, emptyPage.getTotal());

        Set<String> actual = new HashSet<String>();
        actual.addAll(taskIds(firstPage));
        actual.addAll(taskIds(secondPage));
        Assert.assertEquals(new HashSet<String>(Arrays.asList(first, second, third)), actual);
    }

    @Test
    public void testListCombinesTypeStatusAndWorkerFilters() throws InterruptedException {
        LeaseBackend backend = createBackend();
        String taskId = submit(backend, PAY_TASK_TYPE, "running");
        submit(backend, MAIL_TASK_TYPE, "other");
        assertRunningGrant(acquire(backend, PAY_TASK_TYPE, WORKER_A, LONG_LEASE_MILLIS),
                taskId, WORKER_A);

        Assert.assertEquals(Collections.singletonList(taskId), taskIds(backend.list(DEFAULT_QUEUE,
                TaskQuery.builder().type(PAY_TASK_TYPE)
                        .status(TaskStatus.RUNNING)
                        .workerId(WORKER_A)
                        .build())));
    }

    private List<String> taskIds(TaskPage page) {
        List<String> taskIds = new ArrayList<String>();
        for (TaskSnapshot task : page.getTasks()) {
            taskIds.add(task.getTaskId());
        }
        return taskIds;
    }
}
