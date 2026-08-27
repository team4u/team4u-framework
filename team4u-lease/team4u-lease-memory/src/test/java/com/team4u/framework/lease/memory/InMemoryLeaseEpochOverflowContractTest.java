package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.AbstractLeaseEpochOverflowContractTest;
import com.team4u.framework.lease.spi.LeaseBackend;

public class InMemoryLeaseEpochOverflowContractTest extends AbstractLeaseEpochOverflowContractTest {
    @Override
    protected LeaseBackend createBackend() {
        return new InMemoryLeaseBackend();
    }
}
