package com.team4u.framework.lease;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 任务分页结果。
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LeaseTaskPage {

    private final long total;
    private final int page;
    private final int pageSize;
    private final List<LeaseTaskRecord> items;

    public List<LeaseTaskRecord> getItems() {
        return items == null ? Collections.emptyList() : Collections.unmodifiableList(items);
    }
}
