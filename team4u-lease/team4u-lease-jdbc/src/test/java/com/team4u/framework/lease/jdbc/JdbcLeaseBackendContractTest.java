package com.team4u.framework.lease.jdbc;

import cn.hutool.db.Db;
import cn.hutool.db.ds.simple.SimpleDataSource;
import com.team4u.framework.lease.*;
import org.junit.Assert;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.SQLException;

public class JdbcLeaseBackendContractTest extends AbstractLeaseBackendContractTest {

    @Override
    protected LeaseBackend createBackend() {
        return new JdbcLeaseBackend(newDataSource());
    }

    @Test
    public void testRescheduleOverridesVisibleTime() throws Exception {
        JdbcLeaseBackend backend = new JdbcLeaseBackend(newDataSource());
        String taskId = publish(backend, "pay", "payload", 200L);

        Thread.sleep(30L);
        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.reschedule(taskId, 20L));
        Thread.sleep(40L);

        LeaseGrant grant = acquire(backend, "worker-a", 100L, 200L);
        Assert.assertNotNull(grant);
        Assert.assertEquals(taskId, grant.getTaskId());
    }

    @Test
    public void testCancelMarksTaskDead() {
        JdbcLeaseBackend backend = new JdbcLeaseBackend(newDataSource());
        String taskId = publish(backend, "pay", "payload");

        Assert.assertEquals(LeaseAdminResult.APPLIED, backend.cancel(taskId));
        Assert.assertEquals(LeaseTaskStatus.DEAD, backend.get(taskId).get().getStatus());
        Assert.assertEquals("cancelled", backend.get(taskId).get().getLastError());
    }

    @Test
    public void testAckClearsPreviousLastError() throws Exception {
        JdbcLeaseBackend backend = new JdbcLeaseBackend(newDataSource());
        String taskId = publish(backend, "pay", "payload");

        LeaseGrant firstGrant = acquire(backend, "worker-a", 100L, 200L);
        Assert.assertEquals(LeaseRuntimeResult.APPLIED, backend.retry(
                firstGrant.getHandle(), 10L, new IllegalStateException("boom")));

        Thread.sleep(20L);
        LeaseGrant secondGrant = acquire(backend, "worker-a", 100L, 200L);
        Assert.assertEquals(LeaseRuntimeResult.APPLIED, backend.ack(secondGrant.getHandle()));

        Assert.assertEquals(LeaseTaskStatus.SUCCEEDED, backend.get(taskId).get().getStatus());
        Assert.assertNull(backend.get(taskId).get().getLastError());
    }

    @Test
    public void testListCanFilterByQueueTaskTypeAndStatus() {
        JdbcLeaseBackend backend = new JdbcLeaseBackend(newDataSource());
        backend.publish(LeasePublishRequest.builder().queue("queue-a").taskType("pay").payload("a").priority(5).build());
        backend.publish(LeasePublishRequest.builder().queue("queue-b").taskType("mail").payload("b").build());

        Assert.assertEquals(1, backend.list(LeaseQueryRequest.builder()
                .queue("queue-a")
                .taskType("pay")
                .status(LeaseTaskStatus.SCHEDULED)
                .build()).getItems().size());
    }

    private DataSource newDataSource() {
        String dbName = "lease_" + System.nanoTime();
        String jdbcUrl = "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        DataSource dataSource = new SimpleDataSource(jdbcUrl, "sa", "");
        try {
            Db.use(dataSource).execute("DROP TABLE IF EXISTS lease_task");
            Db.use(dataSource).execute("CREATE TABLE lease_task ("
                    + "task_id VARCHAR(64) NOT NULL PRIMARY KEY,"
                    + "queue_name VARCHAR(128) NOT NULL,"
                    + "task_type VARCHAR(128) NOT NULL,"
                    + "payload TEXT,"
                    + "status VARCHAR(32) NOT NULL,"
                    + "priority INT NOT NULL DEFAULT 0,"
                    + "delivery_count INT NOT NULL DEFAULT 0,"
                    + "failure_count INT NOT NULL DEFAULT 0,"
                    + "worker_id VARCHAR(128),"
                    + "lease_token VARCHAR(128),"
                    + "lease_expires_at BIGINT NOT NULL DEFAULT 0,"
                    + "visible_at BIGINT NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "updated_at BIGINT NOT NULL,"
                    + "last_error TEXT,"
                    + "attributes_json TEXT"
                    + ")");
            Db.use(dataSource).execute(
                    "CREATE INDEX idx_lease_task_acquire ON lease_task(queue_name, status, visible_at, lease_expires_at, priority, created_at)");
            Db.use(dataSource).execute(
                    "CREATE INDEX idx_lease_task_worker ON lease_task(worker_id, status)");
            Db.use(dataSource).execute(
                    "CREATE INDEX idx_lease_task_type ON lease_task(queue_name, task_type, status)");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to initialize H2 schema", e);
        }
        return dataSource;
    }
}
