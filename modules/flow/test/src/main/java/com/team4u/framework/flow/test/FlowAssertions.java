package com.team4u.framework.flow.test;

import com.team4u.framework.flow.Failure;
import com.team4u.framework.flow.FlowResult;
import com.team4u.framework.flow.Outcome;
import com.team4u.framework.flow.Reason;
import com.team4u.framework.flow.ResumePoint;
import com.team4u.framework.flow.Suspension;
import com.team4u.framework.flow.durable.DurableResult;
import com.team4u.framework.flow.durable.DurableSnapshot;
import org.junit.Assert;

import java.util.Objects;

/**
 * 针对四态 Outcome、Local 三态 FlowResult 与 DurableResult 的聚焦静态断言。
 *
 * <p>全部断言失败时抛出携带可读消息的 AssertionError，消息包含期望与实际。</p>
 */
public final class FlowAssertions {
    private FlowAssertions() { }

    // ------------------------------------------------------------------
    // FlowResult（Local）
    // ------------------------------------------------------------------

    /** 断言 Local 结果为 Completed 并返回其 Outcome。 */
    public static <O> Outcome<O> assertCompleted(FlowResult<O> result) {
        Objects.requireNonNull(result, "result must not be null");
        Assert.assertTrue("expected FlowResult to be Completed but was <"
                        + describe(result) + ">",
                result instanceof FlowResult.Completed);
        return ((FlowResult.Completed<O>) result).outcome();
    }

    /** 断言 Local 结果为 Completed/Accepted 且值相等，返回 Accepted 值。 */
    public static <O> O assertAccepted(FlowResult<O> result, O expected) {
        Objects.requireNonNull(expected, "expected value must not be null");
        Outcome<O> outcome = assertCompleted(result);
        Assert.assertTrue("expected Completed outcome to be Accepted but was <"
                + outcome + ">", outcome instanceof Outcome.Accepted);
        O actual = ((Outcome.Accepted<O>) outcome).value();
        Assert.assertEquals("expected Accepted value to match", expected, actual);
        return actual;
    }

    /** 断言 Local 结果为 Completed/Rejected 且 reason.code 相等，返回 Reason。 */
    public static <O> Reason assertRejected(FlowResult<O> result, String expectedCode) {
        requireCode(expectedCode);
        Outcome<O> outcome = assertCompleted(result);
        Assert.assertTrue("expected Completed outcome to be Rejected but was <"
                + outcome + ">", outcome instanceof Outcome.Rejected);
        return assertReasonCode(((Outcome.Rejected<O>) outcome).reason(), expectedCode);
    }

    /** 断言 Local 结果为 Completed/Skipped 且 reason.code 相等，返回 Reason。 */
    public static <O> Reason assertSkipped(FlowResult<O> result, String expectedCode) {
        requireCode(expectedCode);
        Outcome<O> outcome = assertCompleted(result);
        Assert.assertTrue("expected Completed outcome to be Skipped but was <"
                + outcome + ">", outcome instanceof Outcome.Skipped);
        return assertReasonCode(((Outcome.Skipped<O>) outcome).reason(), expectedCode);
    }

    /** 断言 Local 结果为 Completed/Failed 且 failure.code 相等，返回 Failure。 */
    public static <O> Failure assertFailed(FlowResult<O> result, String expectedCode) {
        requireCode(expectedCode);
        Outcome<O> outcome = assertCompleted(result);
        Assert.assertTrue("expected Completed outcome to be Failed but was <"
                + outcome + ">", outcome instanceof Outcome.Failed);
        Failure failure = ((Outcome.Failed<O>) outcome).failure();
        Assert.assertEquals("expected Failure code to match", expectedCode, failure.code());
        return failure;
    }

    /** 断言 Local 结果为 Suspended 且挂起点为 point.name，返回 Suspension。 */
    public static <O> Suspension<O> assertSuspended(FlowResult<O> result, ResumePoint<?> point) {
        Objects.requireNonNull(point, "point must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Assert.assertTrue("expected FlowResult to be Suspended but was <"
                        + describe(result) + ">",
                result instanceof FlowResult.Suspended);
        Suspension<O> suspension = ((FlowResult.Suspended<O>) result).suspension();
        Assert.assertEquals("expected suspension resume point to match",
                point.name(), suspension.resumePoint());
        return suspension;
    }

    /** 断言 Local 结果为 Cancelled，返回 executionId。 */
    public static <O> String assertCancelled(FlowResult<O> result) {
        Objects.requireNonNull(result, "result must not be null");
        Assert.assertTrue("expected FlowResult to be Cancelled but was <"
                        + describe(result) + ">",
                result instanceof FlowResult.Cancelled);
        return ((FlowResult.Cancelled<O>) result).executionId();
    }

    // ------------------------------------------------------------------
    // DurableResult
    // ------------------------------------------------------------------

    /** 断言 Durable 结果为 Completed 并返回其 Outcome。 */
    public static <O> Outcome<O> assertCompleted(DurableResult<O> result) {
        Objects.requireNonNull(result, "result must not be null");
        Assert.assertTrue("expected DurableResult to be Completed but was <"
                        + describe(result) + ">",
                result instanceof DurableResult.Completed);
        return ((DurableResult.Completed<O>) result).outcome();
    }

    /** 断言 Durable 结果为 Completed/Accepted 且值相等，返回 Accepted 值。 */
    public static <O> O assertAccepted(DurableResult<O> result, O expected) {
        Objects.requireNonNull(expected, "expected value must not be null");
        Outcome<O> outcome = assertCompleted(result);
        Assert.assertTrue("expected Completed durable outcome to be Accepted but was <"
                + outcome + ">", outcome instanceof Outcome.Accepted);
        O actual = ((Outcome.Accepted<O>) outcome).value();
        Assert.assertEquals("expected Accepted value to match", expected, actual);
        return actual;
    }

    /** 断言 Durable 结果为 Suspended 且挂起点名称匹配，返回 resumePoint 名称。 */
    public static <O> String assertSuspended(DurableResult<O> result, String pointName) {
        Objects.requireNonNull(pointName, "pointName must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Assert.assertTrue("expected DurableResult to be Suspended but was <"
                        + describe(result) + ">",
                result instanceof DurableResult.Suspended);
        DurableResult.Suspended<O> suspended = (DurableResult.Suspended<O>) result;
        Assert.assertEquals("expected suspended resume point to match",
                pointName, suspended.resumePoint());
        return suspended.resumePoint();
    }

    /**
     * 断言 Durable 结果为 Active（退避等待）。requireWakeAt 为 true 时还要求
     * 快照携带唤醒时间（Retry/PersistentPolicy 的 wake）。
     */
    public static <O> DurableResult.Active<O> assertActive(
            DurableResult<O> result, boolean requireWakeAt) {
        Objects.requireNonNull(result, "result must not be null");
        Assert.assertTrue("expected DurableResult to be Active but was <"
                        + describe(result) + ">",
                result instanceof DurableResult.Active);
        DurableResult.Active<O> active = (DurableResult.Active<O>) result;
        if (requireWakeAt && !active.wakeAt().isPresent()) {
            throw new AssertionError("expected Active result to carry wakeAt but it was absent");
        }
        return active;
    }

    /** 断言 Durable 结果为 Active 且携带 wakeAt。 */
    public static <O> DurableResult.Active<O> assertActive(DurableResult<O> result) {
        return assertActive(result, true);
    }

    /** 断言 Durable 结果为 Cancelled。 */
    public static <O> DurableSnapshot assertCancelled(DurableResult<O> result) {
        Objects.requireNonNull(result, "result must not be null");
        Assert.assertTrue("expected DurableResult to be Cancelled but was <"
                        + describe(result) + ">",
                result instanceof DurableResult.Cancelled);
        return result.snapshot();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static Reason assertReasonCode(Reason reason, String expectedCode) {
        Assert.assertEquals("expected Reason code to match", expectedCode, reason.code());
        return reason;
    }

    private static void requireCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("expected code must not be blank");
        }
    }

    private static String describe(FlowResult<?> result) {
        if (result instanceof FlowResult.Completed) {
            return "Completed[" + ((FlowResult.Completed<?>) result).outcome() + "]";
        }
        if (result instanceof FlowResult.Suspended) {
            return "Suspended[resumePoint="
                    + ((FlowResult.Suspended<?>) result).suspension().resumePoint() + "]";
        }
        return result.getClass().getSimpleName();
    }

    private static String describe(DurableResult<?> result) {
        DurableSnapshot snapshot = result.snapshot();
        return result.getClass().getSimpleName() + "[lifecycle=" + snapshot.lifecycle()
                + ", revision=" + snapshot.revision() + "]";
    }
}
