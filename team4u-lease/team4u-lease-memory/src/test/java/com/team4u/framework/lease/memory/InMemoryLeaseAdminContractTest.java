package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.AbstractLeaseAdminContractTest;
import com.team4u.framework.lease.api.LeaseBackend;

public class InMemoryLeaseAdminContractTest extends AbstractLeaseAdminContractTest {

    @Override
    protected LeaseBackend createBackend() {
        return new InMemoryLeaseBackend();
    }
}
