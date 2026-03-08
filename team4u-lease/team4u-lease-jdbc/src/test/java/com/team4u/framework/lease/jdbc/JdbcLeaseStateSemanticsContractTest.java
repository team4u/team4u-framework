package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.AbstractLeaseStateSemanticsContractTest;
import com.team4u.framework.lease.api.LeaseBackend;

public class JdbcLeaseStateSemanticsContractTest extends AbstractLeaseStateSemanticsContractTest {

    @Override
    protected LeaseBackend createBackend() {
        return new JdbcLeaseBackend(JdbcLeaseBackendTestSupport.newDataSource());
    }
}
