package com.team4u.framework.lease.spi;

public interface LeaseBackend extends LeasePublisher, LeaseRuntimeClient, LeaseAdminService,
        LeaseQueryService {
}
