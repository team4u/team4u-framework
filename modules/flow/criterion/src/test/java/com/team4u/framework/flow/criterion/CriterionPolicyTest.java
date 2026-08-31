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

import java.util.Arrays;
import java.util.Collections;
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
}
