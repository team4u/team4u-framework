package com.team4u.framework.flow.durable;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Objects;

/**
 * 持久化流引擎统一异常（Durable Domain Exception）。
 *
 * <p>封装持久化存储、版本冲突、快照编解码、拓扑校验及生命周期调度过程中的所有错误，包含固定的错误码枚举 {@link Error}。</p>
 *
 * @author jay.wu
 */
@Getter
@Accessors(fluent = true)
public final class DurableException extends RuntimeException {
    /**
     * Durable 错误码枚举。
     */
    public enum Error {
        /** 非法流程定义。 */
        INVALID_DEFINITION,
        /** 非法运行时配置。 */
        INVALID_CONFIGURATION,
        /** 流程执行 ID 已存在（重复 start）。 */
        EXECUTION_EXISTS,
        /** 流程执行实例不存在。 */
        EXECUTION_NOT_FOUND,
        /** 快照所属流程 flowId/flowVersion 与当前执行器不匹配。 */
        FLOW_MISMATCH,
        /** 快照格式或版本不匹配。 */
        FORMAT_MISMATCH,
        /** 恢复时快照中的帧栈拓扑与当前代码不匹配。 */
        FRAME_MISMATCH,
        /** 业务数据编解码失败。 */
        CODEC_FAILURE,
        /** 持久化存储后端发生 I/O 或数据库异常。 */
        STORE_FAILURE,
        /** CAS 乐观锁版本冲突。 */
        REVISION_CONFLICT,
        /** 当前生命周期不允许执行该操作。 */
        LIFECYCLE_MISMATCH,
        /** 恢复信号的目标挂起点与当前等待点不一致。 */
        RESUME_POINT_MISMATCH,
        /** 目标挂起点已持久化了不同的恢复信号。 */
        RESUME_SIGNAL_CONFLICT,
        /** 执行异步操作时未提供 ExecutorService。 */
        ASYNC_EXECUTOR_MISSING
    }

    /** 错误码。 */
    private final Error error;

    /**
     * 构造 Durable 异常。
     *
     * @param error   错误码枚举，不能为 null
     * @param message 错误说明
     */
    public DurableException(Error error, String message) {
        super(message);
        this.error = Objects.requireNonNull(error, "error must not be null");
    }

    /**
     * 构造包含底层根因的 Durable 异常。
     *
     * @param error   错误码枚举，不能为 null
     * @param message 错误说明
     * @param cause   底层异常根因
     */
    public DurableException(Error error, String message, Throwable cause) {
        super(message, cause);
        this.error = Objects.requireNonNull(error, "error must not be null");
    }
}

