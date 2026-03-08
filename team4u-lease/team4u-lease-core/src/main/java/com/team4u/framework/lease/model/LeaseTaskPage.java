package com.team4u.framework.lease.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 任务分页查询结果包装类
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LeaseTaskPage {

    /**
     * 符合查询条件的总任务数
     */
    private final long total;
    /**
     * 当前页码（从 0 开始）
     */
    private final int page;
    /**
     * 每页期望显示的记录条数
     */
    private final int pageSize;
    /**
     * 当前页的任务记录列表
     */
    private final List<LeaseTaskRecord> items;

    public List<LeaseTaskRecord> getItems() {
        return items == null ? Collections.emptyList() : Collections.unmodifiableList(items);
    }
}
