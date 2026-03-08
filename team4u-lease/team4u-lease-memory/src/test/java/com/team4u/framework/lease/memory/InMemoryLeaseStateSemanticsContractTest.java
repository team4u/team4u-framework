package com.team4u.framework.lease.memory;

import com.team4u.framework.lease.AbstractLeaseStateSemanticsContractTest;
import com.team4u.framework.lease.api.LeaseBackend;

public class InMemoryLeaseStateSemanticsContractTest extends AbstractLeaseStateSemanticsContractTest {

    @Override
    protected LeaseBackend createBackend() {
        return new InMemoryLeaseBackend();
    }
}
