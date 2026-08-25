# 运维管控与查询服务

`team4u-lease` 提供了面向控制台检索、巡检大盘、自动化补偿脚本及人工干预的完整查询与管理服务接口。

---

## 任务查询服务 (`LeaseQueryService`)

`LeaseQueryService` 提供了根据任务 ID、业务幂等键以及多维组合条件的分页检索能力：

```java
import com.team4u.framework.lease.api.LeaseQueryService;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import com.team4u.framework.lease.enums.LeaseTaskState;
import com.team4u.framework.lease.model.LeaseQueryRequest;
import com.team4u.framework.lease.model.LeaseTaskPage;
import com.team4u.framework.lease.model.LeaseTaskRecord;

import java.util.Optional;

LeaseQueryService queryService = backend;

// 1. 根据全局唯一 taskId 查询任务快照
Optional<LeaseTaskRecord> task = queryService.get("lease-task-1001");
task.ifPresent(record -> {
    System.out.println("任务状态: " + record.getState());
    System.out.println("投递尝试次数: " + record.getDeliveryCount());
    System.out.println("扩展属性: " + record.getAttributes());
});

// 2. 根据 taskGroup + businessKey 业务唯一键精确查询
Optional<LeaseTaskRecord> orderTask = queryService.getByBusinessKey("order-center", "ORDER_99882026");

// 3. 多维度条件分页检索（支持状态、结果、失败原因、Worker ID 等过滤）
LeaseTaskPage page = queryService.list(LeaseQueryRequest.builder()
        .taskGroup("order-center")
        .taskType("order-timeout-cancel")
        .state(LeaseTaskState.CLOSED)
        .outcome(LeaseTaskOutcome.FAILED)
        .page(0)
        .pageSize(20)
        .build());

System.out.printf("符合条件的任务总数: %d, 当前页条数: %d%n", page.getTotal(), page.getItems().size());
for (LeaseTaskRecord record : page.getItems()) {
    System.out.printf("失败任务 ID: %s, 失败诱因: %s, 错误详情: %s%n",
            record.getTaskId(),
            record.getFailureReason(),
            record.getErrorMessage());
}
```

---

## 运维管控服务 (`LeaseAdminService`)

针对异常、滞留或需要人工修正的任务，`LeaseAdminService` 提供了重新调度、强制关闭与属性更新能力。

所有管理操作均返回 `LeaseAdminResult` 状态枚举，便于调用方准确识别执行结果：

| 返回结果 | 含义 | 说明与处理建议 |
| :--- | :--- | :--- |
| **`APPLIED`** | 操作已成功生效。 | 任务状态已更新 |
| **`TASK_NOT_FOUND`** | 目标任务 ID 不存在。 | 确认 taskId 是否正确 |
| **`CLOSED`** | 任务已处于终态；或在调用 `rescheduleFailed` 时任务非 `CLOSED+FAILED`。 | 无法重复关闭或重调度非失败任务 |
| **`ACTIVE_LEASE_PRESENT`** | 目标任务当前正在 `RUNNING` 且持有有效租约（`lease_expires_at >= now`）。 | **安全防御**：拒绝强行覆盖活跃执行中的任务，避免并发数据错乱 |

---

### 管理操作示例

```java
import com.team4u.framework.lease.api.LeaseAdminService;
import com.team4u.framework.lease.enums.LeaseAdminResult;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseUpdateRequest;

LeaseAdminService adminService = backend;

// 1. 将失败关闭的任务重新拉起进入 READY 队列（立即就绪）
// 注意：rescheduleFailed 仅对 state=CLOSED 且 outcome=FAILED 的任务生效
LeaseAdminResult r1 = adminService.rescheduleFailed("lease-task-failed-001", 0L);
if (r1 == LeaseAdminResult.APPLIED) {
    System.out.println("已成功将失败任务重新入队调度");
}

// 2. 重新调度非终态任务（推迟 10 分钟后再可见）
LeaseAdminResult r2 = adminService.reschedule("lease-task-002", 10 * 60 * 1000L);

// 3. 人工强制关闭任务（标记为 CANCELLED）
LeaseAdminResult r3 = adminService.close("lease-task-003", 
        LeaseCloseRequest.cancelled("人工在管理后台取消"));

// 4. 部分更新任务数据与扩展属性 (仅更新非 null 字段)
LeaseAdminResult r4 = adminService.update(LeaseUpdateRequest.builder()
        .taskId("lease-task-004")
        .payload("{\"orderId\": \"1001\", \"corrected\": true}")
        .priority(50) // 提升优先级
        .attribute("operator", "admin_jay")
        .build());

// 5. 原子更新载荷并立即重新调度
LeaseAdminResult r5 = adminService.updateAndReschedule(
        LeaseUpdateRequest.builder()
                .taskId("lease-task-005")
                .payload("{\"orderId\": \"1005\", \"retryParam\": \"fast\"}")
                .build(),
        0L // delayMillis = 0 表示立即就绪
);
```

