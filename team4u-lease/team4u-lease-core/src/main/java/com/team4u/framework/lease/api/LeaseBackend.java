package com.team4u.framework.lease.api;

/**
 * 组合型后端接口。
 */
public interface LeaseBackend extends LeaseProducer, LeaseRuntimeClient, LeaseAdminService, LeaseQueryService {
}
