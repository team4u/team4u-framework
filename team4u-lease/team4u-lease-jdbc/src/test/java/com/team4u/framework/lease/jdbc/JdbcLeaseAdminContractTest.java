package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.AbstractLeaseAdminContractTest;
import com.team4u.framework.lease.api.LeaseBackend;

public class JdbcLeaseAdminContractTest extends AbstractLeaseAdminContractTest {

    @Override
    protected LeaseBackend createBackend() {
        return new JdbcLeaseBackend(JdbcLeaseBackendTestSupport.newDataSource());
    }
}
