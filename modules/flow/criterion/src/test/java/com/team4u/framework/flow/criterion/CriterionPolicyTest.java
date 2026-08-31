package com.team4u.framework.flow.criterion;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Reason;
import com.team4u.framework.flow.test.FlowAssertions;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.Test;

import java.util.List;

public class CriterionPolicyTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserRequest {
        private String userId;
        private int age;
        private boolean blacklisted;
        private int riskScore;
        private List<String> tags;
    }

    @Test
    public void testPermitIfPolicy() {
        CriterionPolicy<UserRequest> policy = CriterionPolicies.permitIf(
                "age >= 18",
                "UNDERAGE",
                "User must be at least 18 years old"
        );

        Flow<UserRequest, String> flow = Flow.<UserRequest, String>step((ctx, req) -> Outcome.accepted("SUCCESS"))
                .policy(policy, req -> req);

        // 成年放行
        FlowResult<String> adultResult = Local.compile(flow).run(new UserRequest("U1", 20, false, 10, null));
        FlowAssertions.assertAccepted(adultResult, "SUCCESS");

        // 未成年拦截（Rejected）
        FlowResult<String> minorResult = Local.compile(flow).run(new UserRequest("U2", 16, false, 10, null));
        FlowAssertions.assertRejected(minorResult, "UNDERAGE");
    }

    @Test
    public void testRejectIfPolicy() {
        CriterionPolicy<UserRequest> policy = CriterionPolicies.rejectIf(
                "blacklisted == true || riskScore > 80",
                "USER_RISK_BLOCKED",
                "User risk exceeds threshold"
        );

        Flow<UserRequest, String> flow = Flow.<UserRequest, String>step((ctx, req) -> Outcome.accepted("SUCCESS"))
                .policy(policy, req -> req);

        // 正常用户放行
        FlowResult<String> normalResult = Local.compile(flow).run(new UserRequest("U1", 25, false, 20, null));
        FlowAssertions.assertAccepted(normalResult, "SUCCESS");

        // 黑名单用户被拦截
        FlowResult<String> blacklistedResult = Local.compile(flow).run(new UserRequest("U2", 25, true, 20, null));
        FlowAssertions.assertRejected(blacklistedResult, "USER_RISK_BLOCKED");

        // 高风控分用户被拦截
        FlowResult<String> highRiskResult = Local.compile(flow).run(new UserRequest("U3", 25, false, 95, null));
        FlowAssertions.assertRejected(highRiskResult, "USER_RISK_BLOCKED");
    }

    @Test
    public void testFailIfPolicy() {
        CriterionPolicy<UserRequest> policy = CriterionPolicies.failIf(
                "riskScore >= 100",
                "FATAL_RISK_BREACH",
                "Fatal risk breach detected"
        );

        Flow<UserRequest, String> flow = Flow.<UserRequest, String>step((ctx, req) -> Outcome.accepted("SUCCESS"))
                .policy(policy, req -> req);

        // 正常通过
        FlowResult<String> normalResult = Local.compile(flow).run(new UserRequest("U1", 25, false, 50, null));
        FlowAssertions.assertAccepted(normalResult, "SUCCESS");

        // 故障失败（Failed）
        FlowResult<String> failResult = Local.compile(flow).run(new UserRequest("U2", 25, false, 100, null));
        FlowAssertions.assertFailed(failResult, "FATAL_RISK_BREACH");
    }

    @Test
    public void testCustomCriteriaEngine() {
        // 自定义包含操作符的 Criteria 实例
        Criteria customCriteria = Criteria.builder()
                .addOperator("startsWith", (actual, expected) ->
                        actual != null && expected != null && actual.toString().startsWith(expected.toString())
                )
                .build();

        CriterionPolicy<UserRequest> policy = CriterionPolicy.<UserRequest>builder()
                .criteria(customCriteria)
                .expression("userId startsWith 'VIP_'")
                .mode(CriterionPolicy.Mode.PERMIT_IF)
                .action(CriterionAction.REJECT)
                .reasonFactory((ctx, key) -> Reason.of("NO_VIP_PREFIX", "User id must start with VIP_"))
                .build();

        Flow<UserRequest, String> flow = Flow.<UserRequest, String>step((ctx, req) -> Outcome.accepted("PREMIUM_ACCESS"))
                .policy(policy, req -> req);

        FlowResult<String> vipResult = Local.compile(flow).run(new UserRequest("VIP_1001", 30, false, 0, null));
        FlowAssertions.assertAccepted(vipResult, "PREMIUM_ACCESS");

        FlowResult<String> normalResult = Local.compile(flow).run(new UserRequest("NORMAL_1001", 30, false, 0, null));
        FlowAssertions.assertRejected(normalResult, "NO_VIP_PREFIX");
    }

    @Test
    public void testPermitIfPolicyWithDefaultCodes() {
        // 单参 permitIf：未指定原因码时使用 DEFAULT_REJECT_CODE / DEFAULT_FAILURE_CODE
        CriterionPolicy<UserRequest> rejectDefault = CriterionPolicies.permitIf("age >= 18");
        Flow<UserRequest, String> flow = Flow.<UserRequest, String>step((ctx, req) -> Outcome.accepted("OK"))
                .policy(rejectDefault, req -> req);

        FlowResult<String> minor = Local.compile(flow).run(new UserRequest("U1", 15, false, 0, null));
        FlowAssertions.assertRejected(minor, CriterionPolicy.DEFAULT_REJECT_CODE);

        CriterionPolicy<UserRequest> failDefault = CriterionPolicy.<UserRequest>builder()
                .expression("age >= 18")
                .mode(CriterionPolicy.Mode.PERMIT_IF)
                .action(CriterionAction.FAIL)
                .build();
        Flow<UserRequest, String> failFlow = Flow.<UserRequest, String>step((ctx, req) -> Outcome.accepted("OK"))
                .policy(failDefault, req -> req);

        FlowResult<String> failed = Local.compile(failFlow).run(new UserRequest("U2", 15, false, 0, null));
        FlowAssertions.assertFailed(failed, CriterionPolicy.DEFAULT_FAILURE_CODE);
    }

    @Test
    public void testInvalidExpressionFailsFastAtConstruction() {
        // 构造期预编译：非法表达式立即抛出，而非等到首次求值
        try {
            CriterionPolicy.<UserRequest>builder()
                    .expression("age >>>> 18 ((")
                    .mode(CriterionPolicy.Mode.PERMIT_IF)
                    .build();
            org.junit.Assert.fail("invalid expression must fail at construction");
        } catch (RuntimeException expected) {
            // 具体异常类型由 criterion 底座决定（CriterionParseException 或其运行时包装）
            org.junit.Assert.assertTrue(expected.getMessage() != null);
        }
    }

    @Test
    public void testInvalidModeActionCombinationRejectedAtConstruction() {
        // REJECT_IF + action=FAIL：无效组合，构造期拒绝
        try {
            CriterionPolicy.<UserRequest>builder()
                    .expression("blacklisted == true")
                    .mode(CriterionPolicy.Mode.REJECT_IF)
                    .action(CriterionAction.FAIL)
                    .build();
            org.junit.Assert.fail("REJECT_IF with action=FAIL must be rejected");
        } catch (IllegalArgumentException expected) {
            org.junit.Assert.assertTrue(expected.getMessage().contains("REJECT_IF"));
        }

        // FAIL_IF + action=REJECT：无效组合，构造期拒绝
        try {
            CriterionPolicy.<UserRequest>builder()
                    .expression("riskScore > 99")
                    .mode(CriterionPolicy.Mode.FAIL_IF)
                    .action(CriterionAction.REJECT)
                    .build();
            org.junit.Assert.fail("FAIL_IF with action=REJECT must be rejected");
        } catch (IllegalArgumentException expected) {
            org.junit.Assert.assertTrue(expected.getMessage().contains("FAIL_IF"));
        }
    }

    @Test
    public void testEvaluationExceptionIsFailClosedAsPolicyException() {
        // 求值异常 → 引擎转化为 POLICY_EXCEPTION(Failed)，fail-closed
        Criteria exploding = new Criteria(null, null) {
            @Override
            public boolean matches(String expression, Object actual) {
                throw new IllegalStateException("criterion engine exploded");
            }
        };
        CriterionPolicy<UserRequest> policy = CriterionPolicy.<UserRequest>builder()
                .criteria(exploding)
                .expression("age >= 18")
                .mode(CriterionPolicy.Mode.PERMIT_IF)
                .build();

        Flow<UserRequest, String> flow = Flow.<UserRequest, String>step((ctx, req) -> Outcome.accepted("OK"))
                .policy(policy, req -> req);
        FlowResult<String> result = Local.compile(flow).run(new UserRequest("U1", 20, false, 0, null));
        FlowAssertions.assertFailed(result, "POLICY_EXCEPTION");
    }
}
