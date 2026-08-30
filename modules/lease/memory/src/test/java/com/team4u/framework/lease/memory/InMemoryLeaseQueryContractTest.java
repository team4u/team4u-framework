package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.AbstractLeaseQueryContractTest;
import com.team4u.framework.lease.spi.LeaseBackend;

public class InMemoryLeaseQueryContractTest extends AbstractLeaseQueryContractTest {
    @Override
    protected LeaseBackend createBackend() {
        return new InMemoryLeaseBackend();
    }
}
