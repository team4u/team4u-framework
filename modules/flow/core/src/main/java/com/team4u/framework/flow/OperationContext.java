package com.team4u.framework.flow;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * 业务步骤（{@link Operation}）执行上下文核心接口。
 *
 * <p>遵循最小暴露原则，向业务代码提供运行期必需的治理支持：
 * <ul>
 *   <li>{@link #metadata()}：当前节点的拓扑元数据（flowId、version、executionId、nodePath、label 等）；</li>
 *   <li>{@link #invocationId()}：单次调用的全局幂等唯一键（格式：{@code flowId:version:executionId:nodePath}），可直接用于外部 RPC/DB 的幂等防重；</li>
 *   <li>{@link #cancellation()}：协作式取消信号，便于长耗时循环协作退出；</li>
 *   <li>{@link #await(CompletionStage)}：安全阻塞等待异步 Future 的便捷方法，内置取消信号级联响应与中断保护。</li>
 * </ul>
 * </p>
 *
 * @author team4u
 */
public interface OperationContext {

    /**
     * 获取当前执行节点的元数据描述。
     *
     * @return 节点元数据 {@link Metadata}，保证非 null
     */
    Metadata metadata();

    /**
     * 获取当前操作执行的全局幂等调用 ID。
     *
     * <p>由流程标识、版本、执行实例 ID 及节点路径确定性拼接而成，
     * 无论重试多少次，同一节点在同一次执行中的 invocationId 保持恒定，
     * 适合作为分布式防重 token 或数据库唯一流水号。</p>
     *
     * @return 全局唯一幂等标识字符串
     */
    String invocationId();

    /**
     * 获取当前流程的协作式取消信号。
     *
     * @return 取消信号 {@link Cancellation.Signal}，保证非 null
     */
    Cancellation.Signal cancellation();

    /**
     * 在当前操作中安全阻塞等待异步 {@link CompletionStage} 完成并解包出结果值。
     *
     * <p>特性保证：
     * <ul>
     *   <li>在阻塞前与阻塞后均会检查取消信号，若已取消则自动 cancel Future 并抛出 {@link CancellationException}；</li>
     *   <li>响应线程物理中断（{@link InterruptedException}），恢复中断标志并 cancel 目标 Future；</li>
     *   <li>自动剥离 {@link ExecutionException} 并重抛底层业务真实异常。</li>
     * </ul>
     * </p>
     *
     * @param stage 异步 Stage，不能为 null
     * @param <T>   返回数据类型
     * @return 异步计算完成的值（不能为 null）
     * @throws NullPointerException  当 {@code stage} 为 null 或计算结果为 null 时抛出
     * @throws CancellationException 当执行已被取消时抛出
     * @throws InterruptedException  当等待线程被中断时抛出
     * @throws Exception             当异步计算过程抛出业务异常时重抛底层异常
     */
    default <T> T await(CompletionStage<T> stage) throws Exception {
        Objects.requireNonNull(stage, "stage must not be null");
        CompletableFuture<T> future = stage.toCompletableFuture();
        if (cancellation().isCancelled()) {
            future.cancel(true);
            throw new CancellationException("flow execution was cancelled");
        }
        try {
            T value = future.get();
            if (cancellation().isCancelled()) {
                throw new CancellationException("flow execution was cancelled");
            }
            return Objects.requireNonNull(value, "awaited value must not be null");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        }
    }
}

