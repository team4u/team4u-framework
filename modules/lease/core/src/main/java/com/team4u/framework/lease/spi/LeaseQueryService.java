package com.team4u.framework.lease.spi;

import com.team4u.framework.lease.api.TaskPage;
import com.team4u.framework.lease.api.TaskQuery;
import com.team4u.framework.lease.api.TaskSnapshot;

import java.util.Optional;

public interface LeaseQueryService {

    Optional<TaskSnapshot> get(String queue, String taskId);

    Optional<TaskSnapshot> getByDeduplicationKey(String queue, String taskType, String key);

    TaskPage list(String queue, TaskQuery query);
}
