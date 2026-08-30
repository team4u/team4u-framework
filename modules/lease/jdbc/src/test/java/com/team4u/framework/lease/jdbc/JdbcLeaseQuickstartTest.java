package com.team4u.framework.lease.jdbc;

import com.team4u.framework.base.jdbc.JdbcUtil;
import com.team4u.framework.lease.api.TaskPage;
import com.team4u.framework.lease.api.TaskQuery;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.jdbc.dialect.MySqlLeaseDbDialect;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.LeaseCompletion;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.LeaseHandle;
import com.team4u.framework.lease.spi.RuntimeResult;
import com.team4u.framework.lease.spi.SubmitCommand;
import com.team4u.framework.lease.spi.SubmitResult;
import com.team4u.framework.lease.spi.TaskSubscription;
import org.junit.Assert;
import org.junit.Test;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

public class JdbcLeaseQuickstartTest {

    @Test
    public void jdbcBackendRunsSchemaAndLeaseLifecycleOnH2() throws Exception {
        AtomicLong currentTime = new AtomicLong(1_000L);
        DataSource dataSource = newUniqueDataSource("quickstart_" + System.nanoTime());
        try {
            initializeSchema(dataSource);
            JdbcLeaseBackend backend = new JdbcLeaseBackend(
                    dataSource, new MySqlLeaseDbDialect(), currentTime::get);
            performLifecycle(backend, currentTime);
        } finally {
            shutdown(dataSource);
        }
    }

    private static void performLifecycle(JdbcLeaseBackend backend, AtomicLong currentTime)
            throws InterruptedException {
        SubmitResult submission = backend.submit(SubmitCommand.of(
                "orders", "email.send", "{\"orderId\":\"O-1001\"}", "O-1001",
                0L, 10, Collections.singletonMap("traceId", "trace-1")));
        Assert.assertTrue(submission.isCreated());
        Assert.assertEquals(TaskStatus.PENDING, submission.getSnapshot().getStatus());
        Assert.assertEquals(0, submission.getSnapshot().getAttemptCount());

        LeaseGrant grant = backend.acquire(AcquireCommand.of(
                TaskSubscription.of("orders", Collections.singleton("email.send")),
                "worker-a", 500L));
        Assert.assertNotNull(grant);
        Assert.assertEquals(submission.getTaskId(), grant.getHandle().getTaskId());
        Assert.assertEquals(TaskStatus.RUNNING, grant.getSnapshot().getStatus());
        Assert.assertEquals("worker-a", grant.getSnapshot().getWorkerId());
        Assert.assertEquals(1, grant.getSnapshot().getAttemptCount());
        Assert.assertNotNull(grant.getHandle().getLeaseToken());
        Assert.assertEquals(Instant.ofEpochMilli(1_500L), grant.getSnapshot().getLeaseExpiresAt());

        currentTime.set(1_200L);
        Assert.assertEquals(RuntimeResult.APPLIED,
                backend.heartbeat(grant.getHandle(), 700L));
        TaskSnapshot running = backend.get("orders", submission.getTaskId()).get();
        Assert.assertEquals(Instant.ofEpochMilli(1_900L), running.getLeaseExpiresAt());
        Assert.assertEquals("trace-1", running.getAttributes().get("traceId"));

        LeaseHandle wrongToken = LeaseHandle.of(grant.getHandle().getTaskId(),
                grant.getHandle().getWorkerId(), "forged-token");
        Assert.assertEquals(RuntimeResult.LEASE_LOST, backend.heartbeat(wrongToken, 100L));

        Assert.assertEquals(RuntimeResult.APPLIED, backend.close(grant.getHandle(),
                LeaseCompletion.succeeded("{\"sent\":true}",
                        Collections.singletonMap("traceId", "trace-1"))));
        TaskSnapshot completed = backend.get("orders", submission.getTaskId()).get();
        Assert.assertEquals(TaskStatus.SUCCEEDED, completed.getStatus());
        Assert.assertEquals("{\"sent\":true}", completed.getPayload());
        Assert.assertEquals("trace-1", completed.getAttributes().get("traceId"));
        Assert.assertNull(completed.getWorkerId());
        Assert.assertNull(completed.getLeaseExpiresAt());

        Assert.assertEquals(RuntimeResult.TERMINAL, backend.close(grant.getHandle(),
                LeaseCompletion.failed("late failure", null, null)));

        SubmitResult duplicate = backend.submit(SubmitCommand.of(
                "orders", "email.send", "{\"orderId\":\"O-1002\"}", "O-1001",
                0L, 0, Collections.<String, String>emptyMap()));
        Assert.assertFalse(duplicate.isCreated());
        Assert.assertEquals(submission.getTaskId(), duplicate.getTaskId());
        Assert.assertEquals(TaskStatus.SUCCEEDED, duplicate.getSnapshot().getStatus());

        TaskPage page = backend.list("orders", TaskQuery.builder()
                .type("email.send").status(TaskStatus.SUCCEEDED).build());
        Assert.assertEquals(1, page.getTotal());
        Assert.assertEquals(1, page.getTasks().size());
        Assert.assertEquals(submission.getTaskId(), page.getTasks().get(0).getTaskId());

        Assert.assertNull(backend.acquire(AcquireCommand.of(
                TaskSubscription.of("orders", Collections.singleton("email.send")),
                "worker-b", 500L)));
    }

    private static DataSource newUniqueDataSource(String databaseName) {
        return new SimpleDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static void initializeSchema(DataSource dataSource) throws SQLException, IOException {
        JdbcUtil.execute(dataSource, "DROP TABLE IF EXISTS lease_task");
        String schemaSql = resourceText("/lease_task_h2.sql");
        int start = 0;
        while (start < schemaSql.length()) {
            int end = schemaSql.indexOf(';', start);
            if (end < 0) {
                end = schemaSql.length();
            }
            String statement = schemaSql.substring(start, end).trim();
            if (!statement.isEmpty()) {
                JdbcUtil.execute(dataSource, statement);
            }
            start = end + 1;
        }
    }

    private static String resourceText(String path) throws IOException {
        try (InputStream input = JdbcLeaseQuickstartTest.class.getResourceAsStream(path)) {
            Assert.assertNotNull(path, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void shutdown(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("SHUTDOWN");
        }
    }

    private static final class SimpleDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;

        private SimpleDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return null;
        }
    }
}
