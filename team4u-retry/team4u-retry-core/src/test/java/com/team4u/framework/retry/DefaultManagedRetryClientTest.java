package com.team4u.framework.retry;

import com.team4u.framework.retry.backoff.Backoffs;
import com.team4u.framework.retry.client.DefaultManagedRetryClient;
import com.team4u.framework.retry.domain.ManagedSubmitResult;
import com.team4u.framework.retry.domain.RecoverySpec;
import com.team4u.framework.retry.domain.RetryTaskSpec;
import com.team4u.framework.retry.domain.store.RetryRequest;
import com.team4u.framework.retry.domain.store.RetryState;
import com.team4u.framework.retry.domain.store.RetryStatus;
import com.team4u.framework.retry.policy.RetryPolicy;
import com.team4u.framework.retry.store.RetryDispatcher;
import com.team4u.framework.retry.store.RetryStore;
import com.team4u.framework.retry.store.record.*;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link DefaultManagedRetryClient} 的单元测试。
 * <p>
 * 主要验证以下核心逻辑：
 * <ul>
 * <li>参数合法性校验：确保必须填写的字段通过校验。</li>
 * <li>前台重试流程：验证即时成功、前台退避休眠及前台尝试次数上限。</li>
 * <li>状态持久化集成：验证在不同生命周期阶段（成功、失败、受理）是否正确调用了存储引擎。</li>
 * <li>后台移交逻辑：验证达到前台尝试上限后，是否正确封装并分派任务至后台调度器。</li>
 * <li>幂等性处理：验证重复提交任务时是否能正确感知并返回已有任务快照。</li>
 * </ul>
 */
public class DefaultManagedRetryClientTest {

    /**
     * 验证 submit 方法是否能正确校验规格中的必填字段（幂等键、运行器、任务类型等）。
     */
    @Test
    public void testSubmitRejectsMissingRequiredFields() {
        RecordingStore store = new RecordingStore();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        DefaultManagedRetryClient client = newClient(store, dispatcher);
        RetryPolicy policy = retryPolicy(2, 1);

        assertRejected(client.submit(spec("idem", null, RecoverySpec.of("recover", "payload"), policy)), "executor");
        assertRejected(client.submit(spec(" ", successTask("ok"), RecoverySpec.of("recover", "payload"), policy)),
                "idempotencyKey");
        assertRejected(client.submit(spec("idem", successTask("ok"), RecoverySpec.of(" ", "payload"), policy)),
                "taskType");
    }

    /**
     * 验证任务在前台首次执行即成功的情形。
     */
    @Test
    public void testForegroundSuccessMarksSucceeded() {
        RecordingStore store = new RecordingStore();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        DefaultManagedRetryClient client = newClient(store, dispatcher);

        ManagedSubmitResult<String> result = client.submit(spec(
                "order-1",
                successTask("done"),
                RecoverySpec.of("recover-payment", "payload"),
                retryPolicy(3, 2)));

        Assert.assertTrue(result instanceof ManagedSubmitResult.Completed);
        Assert.assertEquals("done", ((ManagedSubmitResult.Completed<String>) result).getValue());
        Assert.assertEquals(list("createIfAbsent", "markSucceeded"), store.operations);
        Assert.assertNull(dispatcher.command);
    }

    /**
     * 验证重复提交相同幂等键的任务时，能否直接返回已有的任务状态而非重新执行。
     */
    @Test
    public void testDuplicateSubmitReturnsAcceptedExistingState() {
        RecordingStore store = new RecordingStore();
        store.created = false;
        store.existingRecord = retryRecord("task-existing", RetryStatus.PROCESSING, null, 1);
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        DefaultManagedRetryClient client = newClient(store, dispatcher);

        ManagedSubmitResult<String> result = client.submit(spec(
                "order-dup",
                successTask("ignored"),
                RecoverySpec.of("recover-payment", "payload"),
                retryPolicy(3, 1)));

        Assert.assertTrue(result instanceof ManagedSubmitResult.Accepted);
        ManagedSubmitResult.Accepted<String> accepted = (ManagedSubmitResult.Accepted<String>) result;
        Assert.assertEquals("task-existing", accepted.getTaskId());
        Assert.assertEquals(RetryStatus.PROCESSING, accepted.getStatus());
        Assert.assertNull(dispatcher.command);
    }

    /**
     * 验证当前台尝试次数耗尽后，任务能否正确分派至后台分发器并返回 ACCEPTED 状态。
     */
    @Test
    public void testForegroundExhaustedSchedulesBackgroundAndReturnsAccepted() {
        RecordingStore store = new RecordingStore();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.result = DispatchResult.builder()
                .taskId("task-1")
                .backendTaskId("backend-1")
                .nextRunAt(Instant.now().plusSeconds(5))
                .build();
        DefaultManagedRetryClient client = newClient(store, dispatcher);
        AtomicInteger attempts = new AtomicInteger();

        ManagedSubmitResult<String> result = client.submit(spec(
                "order-2",
                () -> {
                    attempts.incrementAndGet();
                    throw new IOException("boom");
                },
                RecoverySpec.of("recover-payment", "payload"),
                retryPolicy(2, 1)));

        Assert.assertTrue(result instanceof ManagedSubmitResult.Accepted);
        ManagedSubmitResult.Accepted<String> accepted = (ManagedSubmitResult.Accepted<String>) result;
        Assert.assertEquals("task-1", accepted.getTaskId());
        Assert.assertEquals(RetryStatus.WAITING_RETRY, accepted.getStatus());
        Assert.assertNotNull(accepted.getNextAttemptAt());
        Assert.assertEquals(2, attempts.get());
        Assert.assertEquals(list("createIfAbsent"), store.operations);
        Assert.assertNotNull(dispatcher.command);
        Assert.assertEquals(2, dispatcher.command.getTransition().getAttempts());
        Assert.assertEquals("IOException", dispatcher.command.getTransition().getLastErrorCode());
        Assert.assertEquals("boom", dispatcher.command.getTransition().getLastErrorMessage());
        Assert.assertEquals("backend-1", dispatcher.command.getRecord().getState().getBackendTaskId());
        Assert.assertNull(store.failedRecord);
    }

    /**
     * 验证在前台退避休眠期间被中断时，任务能否正确标记为最终失败且释放线程。
     */
    @Test
    public void testInterruptedForegroundBackoffMarksFailed() {
        RecordingStore store = new RecordingStore();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        DefaultManagedRetryClient client = newClient(store, dispatcher);

        try {
            Thread.currentThread().interrupt();
            ManagedSubmitResult<String> result = client.submit(spec(
                    "order-3",
                    () -> {
                        throw new IOException("retry-me");
                    },
                    RecoverySpec.of("recover-payment", "payload"),
                    retryPolicy(2, 1, 10L)));

            Assert.assertTrue(result instanceof ManagedSubmitResult.Failed);
            Throwable error = ((ManagedSubmitResult.Failed<String>) result).getError();
            Assert.assertTrue(error instanceof InterruptedException);
            Assert.assertTrue(Thread.currentThread().isInterrupted());
            Assert.assertEquals(list("createIfAbsent", "markFailed"), store.operations);
            Assert.assertEquals("InterruptedException", store.failedRecord.getErrorCode());
            Assert.assertNull(dispatcher.command);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void testManagedRetryBudgetsMapToExpectedExecutionCounts() {
        assertManagedExecutionCount(0, 0, 1, ManagedSubmitResult.Failed.class);
        assertManagedExecutionCount(1, 1, 2, ManagedSubmitResult.Failed.class);
        assertManagedExecutionCount(2, 1, 2, ManagedSubmitResult.Accepted.class);
    }

    private DefaultManagedRetryClient newClient(RecordingStore store, RecordingDispatcher dispatcher) {
        return DefaultManagedRetryClient.builder()
                .store(store)
                .dispatcher(dispatcher)
                .build();
    }

    private RetryPolicy retryPolicy(int maxRetries, int foregroundMaxRetries) {
        return retryPolicy(maxRetries, foregroundMaxRetries, 0L);
    }

    private RetryPolicy retryPolicy(int maxRetries, int foregroundMaxRetries, long delayMillis) {
        return RetryPolicy.builder()
                .maxRetries(maxRetries)
                .foregroundMaxRetries(foregroundMaxRetries)
                .backoff(Backoffs.fixed(delayMillis))
                .retryOn(IOException.class)
                .build();
    }

    private Callable<String> successTask(String value) {
        return () -> value;
    }

    private RetryTaskSpec<String> spec(
            String idempotencyKey,
            Callable<String> executor,
            RecoverySpec recoverySpec,
            RetryPolicy policy) {
        return RetryTaskSpec.<String>builder()
                .idempotencyKey(idempotencyKey)
                .executor(executor)
                .recovery(recoverySpec)
                .policy(policy)
                .build();
    }

    private void assertRejected(ManagedSubmitResult<String> result, String reasonPart) {
        Assert.assertTrue(result instanceof ManagedSubmitResult.Rejected);
        Assert.assertTrue(((ManagedSubmitResult.Rejected<String>) result).getReason().contains(reasonPart));
    }

    private List<String> list(String... items) {
        List<String> values = new ArrayList<String>();
        Collections.addAll(values, items);
        return values;
    }

    private void assertManagedExecutionCount(
            int maxRetries,
            int foregroundMaxRetries,
            int expectedAttempts,
            Class<?> expectedResultType) {
        RecordingStore store = new RecordingStore();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.result = DispatchResult.builder()
                .taskId("task-1")
                .backendTaskId("backend-1")
                .nextRunAt(Instant.now().plusSeconds(1))
                .build();
        DefaultManagedRetryClient client = newClient(store, dispatcher);
        AtomicInteger attempts = new AtomicInteger();

        ManagedSubmitResult<String> result = client.submit(spec(
                "budget-" + maxRetries + "-" + foregroundMaxRetries,
                () -> {
                    attempts.incrementAndGet();
                    throw new IOException("boom");
                },
                RecoverySpec.of("recover-payment", "payload"),
                retryPolicy(maxRetries, foregroundMaxRetries)));

        Assert.assertTrue(expectedResultType.isInstance(result));
        Assert.assertEquals(expectedAttempts, attempts.get());
    }

    private RetryRecord retryRecord(String taskId, RetryStatus status, Instant nextRunAt, int attempts) {
        return RetryRecord.builder()
                .taskId(taskId)
                .request(RetryRequest.builder()
                        .taskId(taskId)
                        .taskType("recover-payment")
                        .idempotencyKey("order-1")
                        .recovery(RecoverySpec.of("recover-payment", "payload"))
                        .policy(retryPolicy(3, 1))
                        .createdAt(Instant.now())
                        .build())
                .state(RetryState.builder()
                        .status(status)
                        .attempts(attempts)
                        .nextRunAt(nextRunAt)
                        .build())
                .build();
    }

    private static class RecordingStore implements RetryStore {
        private final List<String> operations = new ArrayList<String>();
        private boolean created = true;
        private RetryRecord existingRecord;
        private FailureRecord failedRecord;

        @Override
        public SubmitRecord createIfAbsent(RetryCreateRequest request) {
            operations.add("createIfAbsent");
            RetryRecord record = existingRecord != null ? existingRecord
                    : RetryRecord.builder()
                    .taskId("task-1")
                    .request(request.getRequest())
                    .state(request.getInitialState())
                    .build();
            if (record.getRequest() != null) {
                record.getRequest().setTaskId(record.getTaskId());
            }
            return SubmitRecord.builder().created(created).record(record).build();
        }

        @Override
        public Optional<RetryRecord> get(String taskId) {
            return Optional.empty();
        }

        @Override
        public Optional<RetryRecord> findByIdempotencyKey(String taskType, String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public void markSucceeded(String taskId, SuccessRecord success) {
            operations.add("markSucceeded");
        }

        @Override
        public void markFailed(String taskId, FailureRecord failure) {
            operations.add("markFailed");
            failedRecord = failure;
        }

        @Override
        public void markCancelled(String taskId, CancelRecord cancel) {
            operations.add("markCancelled");
        }

        @Override
        public void markWaitingRetry(String taskId, RetryTransition transition) {
            operations.add("markWaitingRetry");
        }

        @Override
        public void markProcessing(String taskId, ProcessingRecord record) {
            operations.add("markProcessing");
        }
    }

    private static class RecordingDispatcher implements RetryDispatcher {
        private RetryDispatchCommand command;
        private DispatchResult result = DispatchResult.builder()
                .taskId("task-1")
                .backendTaskId("backend-1")
                .nextRunAt(Instant.now())
                .build();

        @Override
        public DispatchResult dispatch(RetryDispatchCommand command) {
            this.command = command;
            command.getRecord().getState().setBackendTaskId(result.getBackendTaskId());
            return result;
        }
    }
}
