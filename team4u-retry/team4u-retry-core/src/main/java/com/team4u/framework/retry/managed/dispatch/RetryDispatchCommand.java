package com.team4u.framework.retry.managed.dispatch;

import com.team4u.framework.retry.managed.store.record.RetryRecord;
import com.team4u.framework.retry.managed.store.record.RetryTransition;
import lombok.Builder;
import lombok.Data;

/**
 * 持久化重试调度（Durable Handoff）命令，封装了由分发器发送给后台调度器的指令信息。
 */
@Data
@Builder
public class RetryDispatchCommand {

    /**
     * 当前完整的重试记录快照
     */
    private RetryRecord record;

    /**
     * 涉及到的状态流转信息，包含尝试次数及错误快照
     */
    private RetryTransition transition;

    /**
     * 距离下一次实际运行的退避延迟毫秒数
     */
    private long delayMillis;
}
