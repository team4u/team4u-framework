package com.team4u.framework.lease.jdbc;

import com.team4u.framework.base.util.IdUtil;
import com.team4u.framework.base.util.StringUtil;
import com.team4u.framework.lease.api.TaskPage;
import com.team4u.framework.lease.api.TaskQuery;
import com.team4u.framework.lease.api.TaskSnapshot;
import com.team4u.framework.lease.api.TaskStatus;
import com.team4u.framework.lease.jdbc.dialect.LeaseDbDialect;
import com.team4u.framework.lease.jdbc.dialect.MySqlLeaseDbDialect;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.AdminResult;
import com.team4u.framework.lease.spi.AdminCompletionCommand;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.lease.spi.LeaseCompletion;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.LeaseHandle;
import com.team4u.framework.lease.spi.LeaseRetry;
import com.team4u.framework.lease.spi.LeaseTimes;
import com.team4u.framework.lease.spi.RescheduleCommand;
import com.team4u.framework.lease.spi.RetryCommand;
import com.team4u.framework.lease.spi.RuntimeResult;
import com.team4u.framework.lease.spi.SubmitCommand;
import com.team4u.framework.lease.spi.SubmitResult;
import com.team4u.framework.lease.spi.UpdateCommand;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.time.Instant;
import java.util.function.LongSupplier;
/**
 * JDBC implementation of the five-state LeaseBackend SPI.
 */
public class JdbcLeaseBackend implements LeaseBackend {

    private static final int ACQUIRE_CANDIDATE_LIMIT = 10;

    private final JdbcLeaseTaskDao dao;
    private final LongSupplier clock;

    public JdbcLeaseBackend(DataSource dataSource) {
        this(dataSource, new MySqlLeaseDbDialect());
    }

    public JdbcLeaseBackend(DataSource dataSource, LeaseDbDialect dialect) {
        this(dataSource, dialect, new MillisClock());
    }

    JdbcLeaseBackend(DataSource dataSource, LeaseDbDialect dialect, LongSupplier clock) {
        this.dao = new JdbcLeaseTaskDao(dataSource, dialect);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SubmitResult submit(SubmitCommand command) {
        long now = now();
        LeaseTaskEntity entity = newPendingEntity(command, now);
        try {
            dao.insert(entity);
            return SubmitResult.of(entity.getTaskId(), true, toSnapshot(entity));
        } catch (SQLException e) {
            if (command.getDeduplicationKey() == null || !isDuplicateKey(e)) {
                throw new IllegalStateException("submit failed", e);
            }
            return findDuplicate(command).orElseThrow(() -> new IllegalStateException(
                    "duplicate task disappeared after unique-key conflict", e));
        }
    }

    @Override
    public LeaseGrant acquire(AcquireCommand command) throws InterruptedException {
        LeaseTimes.plusMillis(now(), positiveMillis(command.getLeaseMillis()));
        List<LeaseTaskEntity> candidates = candidates(command.getSubscription());
        for (LeaseTaskEntity candidate : candidates) {
            LeaseGrant grant = tryAcquire(command, candidate);
            if (grant != null) {
                return grant;
            }
        }
        // Every candidate lost its CAS race; returning null lets the worker back off.
        return null;
    }

    @Override
    public RuntimeResult heartbeat(LeaseHandle handle, long extendMillis) {
        final long now = now();
        final long leaseExpiresAt = LeaseTimes.plusMillis(now, positiveMillis(extendMillis));
        return runtimeResult(handle, sql(new SqlCall<Integer>() {
            @Override
            public Integer execute() throws SQLException {
                return Integer.valueOf(dao.heartbeat(handle.getTaskId(),
                        handle.getWorkerId(), handle.getLeaseToken(), leaseExpiresAt, now));
            }
        }));
    }

    @Override
    public RuntimeResult close(LeaseHandle handle, final LeaseCompletion completion) {
        final LeaseHandle safeHandle = handle;
        final long now = now();
        return runtimeResult(handle, sql(new SqlCall<Integer>() {
            @Override
            public Integer execute() throws SQLException {
                return Integer.valueOf(dao.close(safeHandle.getTaskId(),
                        safeHandle.getWorkerId(), safeHandle.getLeaseToken(), completion, now));
            }
        }));
    }

    @Override
    public RuntimeResult release(LeaseHandle handle, final LeaseRetry retry) {
        final LeaseHandle safeHandle = handle;
        final long now = now();
        final long visibleAt = LeaseTimes.plusMillis(now, retry.getDelayMillis());
        return runtimeResult(handle, sql(new SqlCall<Integer>() {
            @Override
            public Integer execute() throws SQLException {
                return Integer.valueOf(dao.release(safeHandle.getTaskId(),
                        safeHandle.getWorkerId(), safeHandle.getLeaseToken(), retry,
                        visibleAt, now));
            }
        }));
    }

    @Override
    public AdminResult complete(final AdminCompletionCommand command) {
        final long now = now();
        int updated = sql(new SqlCall<Integer>() {
            @Override
            public Integer execute() throws SQLException {
                return Integer.valueOf(dao.complete(command.getQueue(), command.getTaskId(),
                        command.getCompletion(), now));
            }
        });
        return adminResult(command.getQueue(), command.getTaskId(), updated);
    }

    @Override
    public AdminResult reschedule(final RescheduleCommand command) {
        final long now = now();
        final long visibleAt = LeaseTimes.plusMillis(now, command.getDelayMillis());
        int updated = sql(new SqlCall<Integer>() {
            @Override
            public Integer execute() throws SQLException {
                return Integer.valueOf(dao.reschedule(command.getQueue(), command.getTaskId(),
                        visibleAt, now));
            }
        });
        return adminResult(command.getQueue(), command.getTaskId(), updated);
    }

    @Override
    public AdminResult retry(final RetryCommand command) {
        final long now = now();
        final long visibleAt = LeaseTimes.plusMillis(now, command.getDelayMillis());
        int updated = sql(new SqlCall<Integer>() {
            @Override
            public Integer execute() throws SQLException {
                return Integer.valueOf(dao.retryFailed(command.getQueue(), command.getTaskId(),
                        visibleAt, now));
            }
        });
        return retryResult(command.getQueue(), command.getTaskId(), updated);
    }

    @Override
    public AdminResult update(final UpdateCommand command) {
        final long now = now();
        int updated = sql(new SqlCall<Integer>() {
            @Override
            public Integer execute() throws SQLException {
                return Integer.valueOf(dao.update(command, now));
            }
        });
        return adminResult(command.getQueue(), command.getTaskId(), updated);
    }

    @Override
    public AdminResult updateAndReschedule(final UpdateCommand command) {
        final long now = now();
        final Long delayMillis = command.getDelayMillis();
        final long visibleAt = LeaseTimes.plusMillis(now,
                delayMillis == null ? 0L : delayMillis.longValue());
        int updated = sql(new SqlCall<Integer>() {
            @Override
            public Integer execute() throws SQLException {
                return Integer.valueOf(dao.updateAndReschedule(command, visibleAt, now));
            }
        });
        return adminResult(command.getQueue(), command.getTaskId(), updated);
    }

    @Override
    public Optional<TaskSnapshot> get(final String queue, final String taskId) {
        return optional(sql(new SqlCall<LeaseTaskEntity>() {
            @Override
            public LeaseTaskEntity execute() throws SQLException {
                return dao.findById(queue, taskId);
            }
        }));
    }

    @Override
    public Optional<TaskSnapshot> getByDeduplicationKey(final String queue, final String taskType,
                                                        final String key) {
        if (StringUtil.isBlank(key)) {
            return Optional.empty();
        }
        return optional(sql(new SqlCall<LeaseTaskEntity>() {
            @Override
            public LeaseTaskEntity execute() throws SQLException {
                return dao.findByDeduplicationKey(queue, taskType, key);
            }
        }));
    }

    @Override
    public TaskPage list(final String queue, final TaskQuery query) {
        final TaskQuery safeQuery = query == null ? TaskQuery.builder().build() : query;
        List<LeaseTaskEntity> entities = sql(new SqlCall<List<LeaseTaskEntity>>() {
            @Override
            public List<LeaseTaskEntity> execute() throws SQLException {
                return dao.query(queue, safeQuery);
            }
        });
        List<TaskSnapshot> snapshots = new ArrayList<TaskSnapshot>();
        for (LeaseTaskEntity entity : entities) {
            snapshots.add(toSnapshot(entity));
        }
        Long total = sql(new SqlCall<Long>() {
            @Override
            public Long execute() throws SQLException {
                return Long.valueOf(dao.count(queue, safeQuery));
            }
        });
        return TaskPage.of(snapshots, safeQuery.getPage(), safeQuery.getPageSize(),
                total.longValue());
    }

    private List<LeaseTaskEntity> candidates(com.team4u.framework.lease.spi.TaskSubscription subscription) {
        return sql(() -> dao.findAcquirableTasks(subscription, now(), ACQUIRE_CANDIDATE_LIMIT));
    }

    private LeaseGrant tryAcquire(AcquireCommand command, LeaseTaskEntity candidate) {
        String leaseToken = IdUtil.simpleUUID();
        long now = now();
        long leaseExpiresAt = LeaseTimes.plusMillis(now, command.getLeaseMillis());
        int updated = sql(() -> dao.tryAcquire(command.getSubscription(), candidate.getTaskId(),
                command.getWorkerId(), leaseToken, leaseExpiresAt, now, candidate.getVersion()));
        if (updated != 1) {
            return null;
        }
        // Re-read by the token written by this CAS. If another worker already took over,
        // that token no longer matches and this acquire must report no grant.
        LeaseTaskEntity acquired = sql(() -> dao.findByLease(
                command.getSubscription().getQueue(), candidate.getTaskId(), command.getWorkerId(),
                leaseToken, candidate.getVersion() + 1L));
        if (acquired == null) {
            return null;
        }
        return LeaseGrant.of(LeaseHandle.of(acquired.getTaskId(), acquired.getWorkerId(),
                acquired.getLeaseToken()), toSnapshot(acquired));
    }

    private LeaseTaskEntity newPendingEntity(SubmitCommand command, long now) {
        return LeaseTaskEntity.builder()
                .taskId(IdUtil.simpleUUID())
                .queueName(command.getQueue())
                .taskType(command.getTaskType())
                .payload(command.getPayload())
                .deduplicationKey(command.getDeduplicationKey())
                .status(TaskStatus.PENDING)
                .priority(command.getPriority())
                .attemptCount(0)
                .visibleAt(LeaseTimes.plusMillis(now, command.getDelayMillis()))
                .createdAt(now)
                .updatedAt(now)
                .version(0L)
                .attributes(command.getAttributes())
                .build();
    }

    private Optional<SubmitResult> findDuplicate(SubmitCommand command) {
        try {
            LeaseTaskEntity existing = dao.findByDeduplicationKey(command.getQueue(),
                    command.getTaskType(), command.getDeduplicationKey());
            if (existing == null) {
                return Optional.empty();
            }
            return Optional.of(SubmitResult.of(existing.getTaskId(), false, toSnapshot(existing)));
        } catch (SQLException lookupException) {
            throw new IllegalStateException("duplicate lookup failed", lookupException);
        }
    }

    private TaskSnapshot toSnapshot(LeaseTaskEntity entity) {
        return TaskSnapshot.builder()
                .taskId(entity.getTaskId())
                .queue(entity.getQueueName())
                .type(entity.getTaskType())
                .payload(entity.getPayload())
                .dedupKey(entity.getDeduplicationKey())
                .status(entity.getStatus())
                .priority(entity.getPriority())
                .attemptCount(entity.getAttemptCount())
                .workerId(entity.getWorkerId())
                .createdAt(Instant.ofEpochMilli(entity.getCreatedAt()))
                .visibleAt(Instant.ofEpochMilli(entity.getVisibleAt()))
                .leaseExpiresAt(entity.getLeaseExpiresAt() == null ? null
                        : Instant.ofEpochMilli(entity.getLeaseExpiresAt().longValue()))
                .errorMessage(entity.getErrorMessage())
                .attributes(entity.getAttributes())
                .build();
    }

    private Optional<TaskSnapshot> optional(LeaseTaskEntity entity) {
        return entity == null ? Optional.<TaskSnapshot>empty()
                : Optional.of(toSnapshot(entity));
    }

    private long now() {
        return clock.getAsLong();
    }

    private static long positiveMillis(long value) {
        if (value <= 0L) {
            throw new IllegalArgumentException("extendMillis must be positive");
        }
        return value;
    }

    private RuntimeResult runtimeResult(LeaseHandle handle, int updated) {
        if (updated == 1) {
            return RuntimeResult.APPLIED;
        }
        LeaseTaskEntity current = sql(() -> dao.findById(handle.getTaskId()));
        if (current == null) {
            return RuntimeResult.TASK_NOT_FOUND;
        }
        if (current.getStatus().isTerminal()) {
            return RuntimeResult.TERMINAL;
        }
        return RuntimeResult.LEASE_LOST;
    }

    private AdminResult adminResult(String queue, String taskId, int updated) {
        if (updated == 1) {
            return AdminResult.APPLIED;
        }
        LeaseTaskEntity current = sql(() -> dao.findById(queue, taskId));
        if (current == null) {
            return AdminResult.TASK_NOT_FOUND;
        }
        if (current.getStatus().isTerminal()) {
            return AdminResult.TERMINAL;
        }
        if (current.getStatus() == TaskStatus.RUNNING
                && current.getLeaseExpiresAt() != null
                && current.getLeaseExpiresAt().longValue() > now()) {
            return AdminResult.ACTIVE_LEASE_PRESENT;
        }
        return AdminResult.TASK_NOT_FOUND;
    }

    private AdminResult retryResult(String queue, String taskId, int updated) {
        if (updated == 1) {
            return AdminResult.APPLIED;
        }
        LeaseTaskEntity current = sql(() -> dao.findById(queue, taskId));
        if (current == null) {
            return AdminResult.TASK_NOT_FOUND;
        }
        if (current.getStatus().isTerminal()) {
            return AdminResult.TERMINAL;
        }
        if (current.getStatus() == TaskStatus.RUNNING
                && current.getLeaseExpiresAt() != null
                && current.getLeaseExpiresAt().longValue() > now()) {
            return AdminResult.ACTIVE_LEASE_PRESENT;
        }
        return AdminResult.TERMINAL;
    }

    private static boolean isDuplicateKey(SQLException exception) {
        SQLException current = exception;
        while (current != null) {
            if (current instanceof SQLIntegrityConstraintViolationException
                    || "23000".equals(current.getSQLState())
                    || "23505".equals(current.getSQLState())
                    || current.getErrorCode() == 23505) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    private <T> T sql(SqlCall<T> call) {
        try {
            return call.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("lease JDBC operation failed", e);
        }
    }

    private interface SqlCall<T> {
        T execute() throws SQLException;
    }

    private static final class MillisClock implements LongSupplier {
        @Override
        public long getAsLong() {
            return System.currentTimeMillis();
        }
    }
}
