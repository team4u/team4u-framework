package com.team4u.framework.retry.runtime.lease;

import com.team4u.framework.lease.memory.InMemoryLeaseBackend;
import com.team4u.framework.retry.api.RetryPolicy;
import com.team4u.framework.retry.common.backoff.Backoffs;
import com.team4u.framework.retry.managed.recovery.RecoveryHandler;
import com.team4u.framework.retry.managed.recovery.RecoveryHandlerRegistry;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.Collections;

public class RetryRuntimeConfigurationContractTest {

    @Test
    public void testDefaultBuildUsesLocalRegistryAndDoesNotModifyGlobal() {
        RecoveryHandlerRegistry.global().unregisterAll();
        try {
            ManagedRetryRuntime runtime = ManagedRetryRuntime.lease(new InMemoryLeaseBackend())
                    .autoScanRecoveryHandlers(false)
                    .defaultPolicy(defaultPolicy())
                    .build();
            try {
                Assert.assertNotSame(RecoveryHandlerRegistry.global(), runtime.registry());
                Assert.assertTrue(runtime.registry().getPolicies().isEmpty());
            } finally {
                runtime.close();
            }
            Assert.assertTrue(RecoveryHandlerRegistry.global().getPolicies().isEmpty());
        } finally {
            RecoveryHandlerRegistry.global().unregisterAll();
        }
    }

    @Test
    public void testDefaultLocalAutoScanLoadsBuiltInStringHandlerWithoutGlobalMutation() {
        RecoveryHandlerRegistry.global().unregisterAll();
        try {
            ManagedRetryRuntime runtime = ManagedRetryRuntime.lease(new InMemoryLeaseBackend())
                    .autoScanRecoveryHandlers(true)
                    .defaultPolicy(defaultPolicy())
                    .build();
            try {
                Assert.assertEquals(
                        Collections.singletonList(ServiceLoadedStringHandler.TASK_NAME),
                        runtime.registry().getPolicies().stream()
                                .map(RecoveryHandler::taskName)
                                .collect(java.util.stream.Collectors.toList()));
            } finally {
                runtime.close();
            }
            Assert.assertTrue(RecoveryHandlerRegistry.global().getPolicies().isEmpty());
        } finally {
            RecoveryHandlerRegistry.global().unregisterAll();
        }
    }

    @Test
    public void testForegroundRecoveryTimeoutIsConfigurableOnRuntimeBuilder() {
        LeaseDurableRetryStore store = new LeaseDurableRetryStore(new InMemoryLeaseBackend());
        Assert.assertEquals(300000L, store.foregroundRecoveryTimeoutMillis());

        ManagedRetryRuntime runtime = ManagedRetryRuntime.lease(new InMemoryLeaseBackend())
                .defaultPolicy(defaultPolicy())
                .foregroundRecoveryTimeout(Duration.ofMillis(500L))
                .build();
        try {
            Assert.assertEquals(500L, runtime.foregroundRecoveryTimeoutMillis());
        } finally {
            runtime.close();
        }
    }

    private static RetryPolicy defaultPolicy() {
        return RetryPolicy.builder()
                .maxRetries(1)
                .foregroundMaxRetries(0)
                .backoff(Backoffs.fixed(1L))
                .build();
    }
}
