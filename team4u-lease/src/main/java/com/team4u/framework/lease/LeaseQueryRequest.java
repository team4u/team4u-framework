package com.team4u.framework.lease;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 任务查询请求。
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LeaseQueryRequest {

    private final String namespace;
    private final String queue;
    private final String taskType;
    @Singular
    private final Set<LeaseTaskStatus> statuses;
    private final String workerId;
    @Builder.Default
    private final int page = 0;
    @Builder.Default
    private final int pageSize = 50;

    public Set<LeaseTaskStatus> getStatuses() {
        if (statuses == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<LeaseTaskStatus>(statuses));
    }
}
