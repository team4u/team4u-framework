package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.AbstractLeaseQueryContractTest;
import com.team4u.framework.lease.spi.LeaseBackend;

public class JdbcLeaseQueryContractTest extends AbstractLeaseQueryContractTest {

    @Override
    protected LeaseBackend createBackend() {
        return new JdbcLeaseBackend(JdbcLeaseBackendTestSupport.newDataSource());
    }
}
