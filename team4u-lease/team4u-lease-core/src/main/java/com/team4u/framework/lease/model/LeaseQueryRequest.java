package com.team4u.framework.lease.model;

import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.enums.LeaseTaskState;
import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 任务查询请求。
 */
@Data
@Builder
public class LeaseQueryRequest {

    private final String queue;
    private final String taskType;
    @Singular
    private final Set<LeaseTaskState> states;
    @Singular
    private final Set<LeaseTaskOutcome> outcomes;
    @Singular
    private final Set<LeaseTaskFailureReason> failureReasons;
    private final String workerId;
    @Builder.Default
    private final int page = 0;
    @Builder.Default
    private final int pageSize = 50;

    public Set<LeaseTaskState> getStates() {
        if (states == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<LeaseTaskState>(states));
    }

    public Set<LeaseTaskOutcome> getOutcomes() {
        if (outcomes == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<LeaseTaskOutcome>(outcomes));
    }

    public Set<LeaseTaskFailureReason> getFailureReasons() {
        if (failureReasons == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<LeaseTaskFailureReason>(failureReasons));
    }
}
