package com.team4u.framework.lease.jdbc;

import com.team4u.framework.lease.AbstractLeaseEpochOverflowContractTest;
import com.team4u.framework.lease.spi.LeaseBackend;

public class JdbcLeaseEpochOverflowContractTest extends AbstractLeaseEpochOverflowContractTest {

    @Override
    protected LeaseBackend createBackend() {
        return new JdbcLeaseBackend(JdbcLeaseBackendTestSupport.newDataSource());
    }
}
