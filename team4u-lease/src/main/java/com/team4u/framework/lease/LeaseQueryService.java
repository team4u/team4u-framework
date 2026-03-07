package com.team4u.framework.lease;

import java.util.Optional;

/**
 * 查询接口。
 */
public interface LeaseQueryService {

    Optional<LeaseTaskRecord> get(String taskId);

    LeaseTaskPage list(LeaseQueryRequest request);
}
