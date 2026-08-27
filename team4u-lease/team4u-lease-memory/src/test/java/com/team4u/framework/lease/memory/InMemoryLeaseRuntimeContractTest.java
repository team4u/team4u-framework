package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.AbstractLeaseRuntimeContractTest;
import com.team4u.framework.lease.spi.LeaseBackend;

public class InMemoryLeaseRuntimeContractTest extends AbstractLeaseRuntimeContractTest {
    @Override
    protected LeaseBackend createBackend() {
        return new InMemoryLeaseBackend();
    }
}
