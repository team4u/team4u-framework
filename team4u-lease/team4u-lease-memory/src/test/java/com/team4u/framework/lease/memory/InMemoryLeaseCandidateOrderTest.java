package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.AbstractLeaseContractSupport;
import com.team4u.framework.lease.spi.AcquireCommand;
import com.team4u.framework.lease.spi.LeaseBackend;
import com.team4u.framework.lease.spi.LeaseGrant;
import com.team4u.framework.lease.spi.SubmitCommand;
import com.team4u.framework.lease.spi.TaskSubscription;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class InMemoryLeaseCandidateOrderTest extends AbstractLeaseContractSupport {

    @Override
    protected LeaseBackend createBackend() {
        return new InMemoryLeaseBackend();
    }

    private static final long NOW_MILLIS = 100_000L;
    @Test
    public void acquireOrdersEligibleCandidatesGloballyAcrossTaskTypes() throws InterruptedException {
        SettableClock clock = new SettableClock(NOW_MILLIS);
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend(clock);

        String laterVisibleLowPriority = submit(backend, PAY_TASK_TYPE, "low", 0, 0L);
        clock.advance(1L);
        String earlierVisibleHighPriority = submit(backend, MAIL_TASK_TYPE, "high", 9, 0L);
        clock.advance(1L);
        String laterCreatedHighPriority = submit(backend, PAY_TASK_TYPE, "high", 9, 0L);

        TaskSubscription subscription = TaskSubscription.of(DEFAULT_QUEUE,
                new LinkedHashSet<String>(Arrays.asList(PAY_TASK_TYPE, MAIL_TASK_TYPE)));

        assertFirstAcquired(backend, subscription, earlierVisibleHighPriority);
        assertFirstAcquired(backend, subscription, laterCreatedHighPriority);
        assertFirstAcquired(backend, subscription, laterVisibleLowPriority);
    }

    @Test
    public void visibleAtFiltersButDoesNotPreemptHigherPriorityCandidate() throws InterruptedException {
        SettableClock clock = new SettableClock(NOW_MILLIS);
        InMemoryLeaseBackend backend = new InMemoryLeaseBackend(clock);

        String lowPriority = submit(backend, PAY_TASK_TYPE, "low", 0, 0L);
        clock.advance(1L);
        String highPriority = submit(backend, MAIL_TASK_TYPE, "high", 9, 10L);
        TaskSubscription subscription = TaskSubscription.of(DEFAULT_QUEUE,
                new LinkedHashSet<String>(Arrays.asList(PAY_TASK_TYPE, MAIL_TASK_TYPE)));

        LeaseGrant first = backend.acquire(AcquireCommand.of(subscription, WORKER_A, 1_000L));
        assertRunningGrant(first, lowPriority, WORKER_A);

        clock.advance(20L);
        LeaseGrant second = backend.acquire(AcquireCommand.of(subscription, WORKER_B, 1_000L));
        assertRunningGrant(second, highPriority, WORKER_B);
    }

    private void assertFirstAcquired(InMemoryLeaseBackend backend, TaskSubscription subscription,
                                    String expectedTaskId) throws InterruptedException {
        LeaseGrant grant = backend.acquire(AcquireCommand.of(subscription, WORKER_A, 1_000L));
        assertRunningGrant(grant, expectedTaskId, WORKER_A);
    }

    private String submit(InMemoryLeaseBackend backend, String taskType, String payload,
                          int priority, long delayMillis) {
        return backend.submit(SubmitCommand.of(DEFAULT_QUEUE, taskType, payload, null,
                delayMillis, priority, Collections.<String, String>emptyMap())).getTaskId();
    }

    private static final class SettableClock extends Clock {
        private volatile long millis;

        private SettableClock(long millis) {
            this.millis = millis;
        }

        void advance(long amount) {
            millis += amount;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
