package com.team4u.framework.retry.store;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对已创建任务的句柄追踪。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskHandle {
    private String taskId;
}
