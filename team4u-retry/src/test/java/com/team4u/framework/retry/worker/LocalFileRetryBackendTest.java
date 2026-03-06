package com.team4u.framework.retry.worker;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class LocalFileRetryBackendTest {

    private static final long PENDING_RECOVER_AFTER_MILLIS = 50L;
    private static final String TASK_TYPE = "pay-notify";
    private static final String PAYLOAD = "{\"orderId\":\"A2001\"}";

    @Test
    public void testPersistAndReloadRecords() throws Exception {
        Path dir = Files.createTempDirectory("retry-backend-test");
        Path file = dir.resolve("BackEndretry.txt");

        try {
            LocalFileRetryBackend backend = createBackend(file);
            String intentId = backend.saveIntent(TASK_TYPE, PAYLOAD);
            backend.submitForDelay(intentId, TASK_TYPE, PAYLOAD, 0L);

            LocalFileRetryBackend reloaded = createBackend(file);
            Map<String, RetryTaskRecord> snapshot = reloaded.snapshot();
            RetryTaskRecord record = snapshot.get(intentId);

            Assert.assertNotNull("重载后应保留任务记录", record);
            Assert.assertEquals(RetryTaskRecord.QUEUED, record.getStatus());
            Assert.assertEquals(TASK_TYPE, record.getTaskType());

            RetryTaskRecord taken = reloaded.take();
            Assert.assertEquals(intentId, taken.getIntentId());
            Assert.assertEquals(PAYLOAD, taken.getPayload());

            reloaded.completeIntent(intentId);

            LocalFileRetryBackend afterComplete = createBackend(file);
            Assert.assertTrue("完成后应从磁盘中移除", afterComplete.snapshot().isEmpty());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    private LocalFileRetryBackend createBackend(Path file) {
        return new LocalFileRetryBackend(file, PENDING_RECOVER_AFTER_MILLIS);
    }
}
