package com.team4u.framework.lease.api;

import java.util.Collections;
import java.util.List;

public final class TaskPage {

    private final List<TaskSnapshot> tasks;
    private final int page;
    private final int pageSize;
    private final long total;

    private TaskPage(List<TaskSnapshot> tasks, int page, int pageSize, long total) {
        this.tasks = Collections.unmodifiableList(new java.util.ArrayList<TaskSnapshot>(tasks));
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
    }

    public static TaskPage of(List<TaskSnapshot> tasks, int page, int pageSize, long total) {
        if (tasks == null) {
            throw new IllegalArgumentException("tasks must not be null");
        }
        if (page < 0 || pageSize < 1 || total < 0L) {
            throw new IllegalArgumentException("page, pageSize and total must be valid");
        }
        return new TaskPage(tasks, page, pageSize, total);
    }

    public List<TaskSnapshot> getTasks() {
        return tasks;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public long getTotal() {
        return total;
    }
}
