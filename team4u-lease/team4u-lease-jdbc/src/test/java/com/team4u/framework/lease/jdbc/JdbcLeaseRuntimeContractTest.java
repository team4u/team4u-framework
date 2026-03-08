package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.AbstractLeaseRuntimeContractTest;
import com.team4u.framework.lease.api.LeaseBackend;

public class JdbcLeaseRuntimeContractTest extends AbstractLeaseRuntimeContractTest {

    @Override
    protected LeaseBackend createBackend() {
        return new JdbcLeaseBackend(JdbcLeaseBackendTestSupport.newDataSource());
    }
}
