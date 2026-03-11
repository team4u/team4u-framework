package com.team4u.framework.lease.model;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 任务分页查询结果包装类
 * <p>
 * 封装分页查询任务的响应数据，包含符合条件的总记录数、当前页码、页大小及当前页的任务列表。
 * <p>
 * <b>分页说明：</b>
 * <ul>
 *     <li>页码从 0 开始计数</li>
 *     <li>总记录数可用于计算总页数：{@code totalPages = (total + pageSize - 1) / pageSize}</li>
 * </ul>
 */
@Data
@Builder
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