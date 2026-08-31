package com.team4u.framework.flow.criterion;

import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.test.FlowAssertions;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CriterionPredicateTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Order {
        private String orderId;
        private int amount;
        private boolean vip;
        private List<String> tags;
    }

    @Test
    public void testDirectPredicateEvaluation() {
        CriterionPredicate<Order> predicate = CriterionPredicates.of("amount >= 100 && vip == true");

        assertTrue(predicate.test(new Order("O-1", 100, true, Arrays.asList("FAST", "GIFT"))));
        assertTrue(predicate.test(new Order("O-2", 200, true, null)));
        assertFalse(predicate.test(new Order("O-3", 50, true, null)));
        assertFalse(predicate.test(new Order("O-4", 100, false, null)));
        assertFalse(predicate.test(null));
    }

    @Test
    public void testMapEvaluation() {
        CriterionPredicate<Map<String, Object>> predicate = CriterionPredicates.of("score > 60 && grade in ['A', 'B']");

        Map<String, Object> passMap = new HashMap<>();
        passMap.put("score", 85);
        passMap.put("grade", "A");
        assertTrue(predicate.test(passMap));

        Map<String, Object> failMap = new HashMap<>();
        failMap.put("score", 50);
        failMap.put("grade", "C");
        assertFalse(predicate.test(failMap));
    }

    @Test
    public void testCustomTargetExtractor() {
        class Wrapper {
            final Order order;
            Wrapper(Order order) { this.order = order; }
        }

        CriterionPredicate<Wrapper> predicate = CriterionPredicates.of(
                "amount > 500",
                Criteria.global(),
                w -> w.order
        );

        assertTrue(predicate.test(new Wrapper(new Order("O-1", 600, false, null))));
        assertFalse(predicate.test(new Wrapper(new Order("O-2", 300, false, null))));
        assertFalse(predicate.test(new Wrapper(null)));
    }

    @Test
    public void testFlowConditionalExecutionWithPredicate() {
        CriterionPredicate<Order> vipCheck = CriterionPredicates.of("vip == true && amount >= 200");

        Flow<Order, String> flow = Flow.<Order, String>step((ctx, order) ->
                vipCheck.test(order)
                        ? Outcome.accepted("DISCOUNT_APPLIED")
                        : Outcome.skipped(com.team4u.framework.flow.model.Reason.of("NOT_ELIGIBLE", "Not eligible"))
        );

        FlowResult<String> vipResult = Local.compile(flow).run(new Order("O-1", 300, true, null));
        FlowAssertions.assertAccepted(vipResult, "DISCOUNT_APPLIED");

        FlowResult<String> nonVipResult = Local.compile(flow).run(new Order("O-2", 100, false, null));
        FlowAssertions.assertSkipped(nonVipResult, "NOT_ELIGIBLE");
    }
}
