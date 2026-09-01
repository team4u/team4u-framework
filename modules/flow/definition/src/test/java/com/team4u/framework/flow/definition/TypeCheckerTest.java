package com.team4u.framework.flow.definition;

import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.definition.type.TypeCheckResult;
import com.team4u.framework.flow.definition.type.TypeChecker;
import com.team4u.framework.flow.definition.type.TypeRef;
import com.team4u.framework.flow.model.Outcome;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

public class TypeCheckerTest {

    static class Order {
        String id;
        int amount;
    }

    static class OrderItem {
        String sku;
    }

    static class Reservation {
        String reservationId;
    }

    static class PaymentRequest {
        String orderId;
    }

    static class PaymentResult {
        boolean success;
    }

    enum OrderStatus {
        PAID,
        CANCELLED
    }

    @Test
    public void testValidSequenceTypeCheck() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.validate", (OperationContext ctx, Order in) -> Outcome.accepted(in), Order.class, Order.class)
                .operation("payment.charge", (OperationContext ctx, Order in) -> Outcome.accepted("PAID_OK"), Order.class, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "order.create", "1",
                new SequenceSpec(Arrays.<FlowSpec>asList(
                        new StepSpec(SymbolRef.of("order.validate"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN),
                        new StepSpec(SymbolRef.of("payment.charge"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN)
                ), SourceSpan.UNKNOWN),
                "order.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry);
        Assert.assertTrue(result.success());
        Assert.assertEquals(TypeRef.of(String.class), result.outputType());
    }

    @Test
    public void testTypeMismatchDiagnostic() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.validate", (OperationContext ctx, Order in) -> Outcome.accepted(in), Order.class, Order.class)
                .operation("payment.charge", (OperationContext ctx, PaymentRequest in) -> Outcome.accepted("PAID_OK"), PaymentRequest.class, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "order.create", "1",
                new SequenceSpec(Arrays.<FlowSpec>asList(
                        new StepSpec(SymbolRef.of("order.validate"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN),
                        new StepSpec(SymbolRef.of("payment.charge"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN)
                ), SourceSpan.UNKNOWN),
                "order.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry);
        Assert.assertFalse(result.success());
        Assert.assertEquals(1, result.diagnostics().size());
        Diagnostic diag = result.diagnostics().get(0);
        Assert.assertEquals(DiagnosticCodes.TYPE_MISMATCH, diag.code());
    }

    @Test
    public void testProjectAndMergeTypeCheck() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .projector("order.items", Order.class, OrderItem.class, order -> new OrderItem())
                .merger("order.withReservation", Order.class, Reservation.class, Order.class, (order, res) -> order)
                .operation("inventory.reserve", (OperationContext ctx, OrderItem item) -> Outcome.accepted(new Reservation()), OrderItem.class, Reservation.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "order.reserve", "1",
                new StepSpec(
                        SymbolRef.of("inventory.reserve"),
                        SymbolRef.of("order.items"),
                        SymbolRef.of("order.withReservation"),
                        Collections.<ModifierSpec>emptyList(),
                        SourceSpan.UNKNOWN
                ),
                "order.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry, TypeRef.of(Order.class));
        Assert.assertTrue(result.success());
        Assert.assertEquals(TypeRef.of(Order.class), result.outputType());
    }

    @Test
    public void testUnknownOperationDiagnostic() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.empty();
        FlowDefinition def = new FlowDefinition(
                1, "test", "1",
                new StepSpec(SymbolRef.of("missing.op"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN),
                "test.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry);
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.UNKNOWN_OPERATION, result.diagnostics().get(0).code());
    }

    @Test
    public void testOptionalStepRequiresSameType() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("transform", (OperationContext ctx, Order in) -> Outcome.accepted("STRING"), Order.class, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "test", "1",
                new StepSpec(
                        SymbolRef.of("transform"),
                        null,
                        null,
                        Collections.<ModifierSpec>singletonList(new OptionalModifierSpec(SourceSpan.UNKNOWN)),
                        SourceSpan.UNKNOWN
                ),
                "test.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry, TypeRef.of(Order.class));
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.INVALID_OPTIONAL_STEP, result.diagnostics().get(0).code());
    }

    @Test
    public void testRouteTypeCheck() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.status", (OperationContext ctx, Order in) -> Outcome.accepted(OrderStatus.PAID), Order.class, OrderStatus.class)
                .operation("order.confirm", (OperationContext ctx, Order in) -> Outcome.accepted("CONFIRMED"), Order.class, String.class)
                .operation("order.cancel", (OperationContext ctx, Order in) -> Outcome.accepted("CANCELLED"), Order.class, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "order.route", "1",
                new RouteSpec(
                        SymbolRef.of("order.status"),
                        Arrays.asList(
                                new CaseSpec("PAID", new StepSpec(SymbolRef.of("order.confirm"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN),
                                new CaseSpec("CANCELLED", new StepSpec(SymbolRef.of("order.cancel"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN)
                        ),
                        new CompleteSpec(CompleteSpec.CompleteKind.SKIPPED, "NO_ROUTE", SourceSpan.UNKNOWN),
                        SourceSpan.UNKNOWN
                ),
                "order.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry, TypeRef.of(Order.class));
        Assert.assertTrue(result.success());
    }

    @Test
    public void testRouteInvalidCaseKey() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.status", (OperationContext ctx, Order in) -> Outcome.accepted(OrderStatus.PAID), Order.class, OrderStatus.class)
                .operation("order.confirm", (OperationContext ctx, Order in) -> Outcome.accepted("CONFIRMED"), Order.class, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "order.route", "1",
                new RouteSpec(
                        SymbolRef.of("order.status"),
                        Collections.singletonList(
                                new CaseSpec("NON_EXISTENT_STATUS_KEY", new StepSpec(SymbolRef.of("order.confirm"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN)
                        ),
                        null,
                        SourceSpan.UNKNOWN
                ),
                "order.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry, TypeRef.of(Order.class));
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.INVALID_ROUTE_CASE, result.diagnostics().get(0).code());
    }

    @Test
    public void testPolicyKeyTypeMismatchWithoutKeyProjection() {
        Policy<String> stringPolicy = (ctx, key) -> Gate.proceed();
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.validate", (OperationContext ctx, Order in) -> Outcome.accepted(in), Order.class, Order.class)
                .policy("rate.limit", stringPolicy, String.class)
                .build();

        // currentType is Order, but policy expects String as key, no key projection provided
        FlowDefinition def = new FlowDefinition(
                1, "order.flow", "1",
                new StepSpec(
                        SymbolRef.of("order.validate"),
                        null,
                        null,
                        Collections.singletonList(new PolicyModifierSpec(SymbolRef.of("rate.limit"), null, Collections.emptyMap(), SourceSpan.UNKNOWN)),
                        SourceSpan.UNKNOWN
                ),
                "order.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry, TypeRef.of(Order.class));
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.TYPE_MISMATCH, result.diagnostics().get(0).code());
    }

    @Test
    public void testPolicyKeyTypeMatchWithKeyProjection() {
        Policy<String> stringPolicy = (ctx, key) -> Gate.proceed();
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.validate", (OperationContext ctx, Order in) -> Outcome.accepted(in), Order.class, Order.class)
                .policy("rate.limit", stringPolicy, String.class)
                .keyProjection("order.userId", Order.class, String.class, (Order order) -> order.id)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "order.flow", "1",
                new StepSpec(
                        SymbolRef.of("order.validate"),
                        null,
                        null,
                        Collections.singletonList(new PolicyModifierSpec(SymbolRef.of("rate.limit"), SymbolRef.of("order.userId"), Collections.emptyMap(), SourceSpan.UNKNOWN)),
                        SourceSpan.UNKNOWN
                ),
                "order.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry, TypeRef.of(Order.class));
        Assert.assertTrue(result.success());
    }

    @Test
    public void testPolicyKeyProjectionInputMismatch() {
        Policy<String> stringPolicy = (ctx, key) -> Gate.proceed();
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.validate", (OperationContext ctx, Order in) -> Outcome.accepted(in), Order.class, Order.class)
                .policy("rate.limit", stringPolicy, String.class)
                .keyProjection("item.sku", OrderItem.class, String.class, (OrderItem item) -> item.sku)
                .build();

        // currentType is Order, but key projection expects OrderItem
        FlowDefinition def = new FlowDefinition(
                1, "order.flow", "1",
                new StepSpec(
                        SymbolRef.of("order.validate"),
                        null,
                        null,
                        Collections.singletonList(new PolicyModifierSpec(SymbolRef.of("rate.limit"), SymbolRef.of("item.sku"), Collections.emptyMap(), SourceSpan.UNKNOWN)),
                        SourceSpan.UNKNOWN
                ),
                "order.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry, TypeRef.of(Order.class));
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.TYPE_MISMATCH, result.diagnostics().get(0).code());
    }

    @Test
    public void testPolicyKeyProjectionOutputMismatch() {
        Policy<String> stringPolicy = (ctx, key) -> Gate.proceed();
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.validate", (OperationContext ctx, Order in) -> Outcome.accepted(in), Order.class, Order.class)
                .policy("rate.limit", stringPolicy, String.class)
                .keyProjection("order.amount", Order.class, Integer.class, (Order order) -> order.amount)
                .build();

        // key projection outputs Integer, but policy expects String
        FlowDefinition def = new FlowDefinition(
                1, "order.flow", "1",
                new StepSpec(
                        SymbolRef.of("order.validate"),
                        null,
                        null,
                        Collections.singletonList(new PolicyModifierSpec(SymbolRef.of("rate.limit"), SymbolRef.of("order.amount"), Collections.emptyMap(), SourceSpan.UNKNOWN)),
                        SourceSpan.UNKNOWN
                ),
                "order.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry, TypeRef.of(Order.class));
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.TYPE_MISMATCH, result.diagnostics().get(0).code());
    }

    @Test
    public void testRouteBranchOutputIncompatible() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.status", (OperationContext ctx, Order in) -> Outcome.accepted(OrderStatus.PAID), Order.class, OrderStatus.class)
                .operation("return.string", (OperationContext ctx, Order in) -> Outcome.accepted("STR"), Order.class, String.class)
                .operation("return.integer", (OperationContext ctx, Order in) -> Outcome.accepted(123), Order.class, Integer.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "order.route", "1",
                new RouteSpec(
                        SymbolRef.of("order.status"),
                        Arrays.asList(
                                new CaseSpec("PAID", new StepSpec(SymbolRef.of("return.string"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN),
                                new CaseSpec("CANCELLED", new StepSpec(SymbolRef.of("return.integer"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN)
                        ),
                        null,
                        SourceSpan.UNKNOWN
                ),
                "order.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry, TypeRef.of(Order.class));
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.TYPE_MISMATCH, result.diagnostics().get(0).code());
    }

    @Test
    public void testFirstApplicableBranchOutputIncompatible() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("return.string", (OperationContext ctx, Order in) -> Outcome.accepted("STR"), Order.class, String.class)
                .operation("return.integer", (OperationContext ctx, Order in) -> Outcome.accepted(123), Order.class, Integer.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "fa.flow", "1",
                new FirstApplicableSpec(
                        Arrays.asList(
                                new StepSpec(SymbolRef.of("return.string"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN),
                                new StepSpec(SymbolRef.of("return.integer"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN)
                        ),
                        SourceSpan.UNKNOWN
                ),
                "fa.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry, TypeRef.of(Order.class));
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.TYPE_MISMATCH, result.diagnostics().get(0).code());
    }

    @Test
    public void testRouteNoTypeCodecDiagnostic() {
        // Custom value object without TypeCodec
        class CustomKey {
            final String code;
            CustomKey(String code) { this.code = code; }
        }

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.key", (OperationContext ctx, Order in) -> Outcome.accepted(new CustomKey("K")), Order.class, CustomKey.class)
                .operation("order.confirm", (OperationContext ctx, Order in) -> Outcome.accepted("CONFIRMED"), Order.class, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "order.route", "1",
                new RouteSpec(
                        SymbolRef.of("order.key"),
                        Collections.singletonList(
                                new CaseSpec("K", new StepSpec(SymbolRef.of("order.confirm"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN)
                        ),
                        null,
                        SourceSpan.UNKNOWN
                ),
                "order.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry, TypeRef.of(Order.class));
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.NO_TYPE_CODEC, result.diagnostics().get(0).code());
    }

    @Test
    public void testRouteDuplicateCaseKeyDiagnostic() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.status", (OperationContext ctx, Order in) -> Outcome.accepted(OrderStatus.PAID), Order.class, OrderStatus.class)
                .operation("order.confirm", (OperationContext ctx, Order in) -> Outcome.accepted("CONFIRMED"), Order.class, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "order.route", "1",
                new RouteSpec(
                        SymbolRef.of("order.status"),
                        Arrays.asList(
                                new CaseSpec("PAID", new StepSpec(SymbolRef.of("order.confirm"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN),
                                new CaseSpec("PAID", new StepSpec(SymbolRef.of("order.confirm"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN)
                        ),
                        null,
                        SourceSpan.UNKNOWN
                ),
                "order.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry, TypeRef.of(Order.class));
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.DUPLICATE_ROUTE_CASE, result.diagnostics().get(0).code());
    }

    @Test
    public void testUnsupportedSpecDiagnostic() {
        class CustomUnregisteredSpec implements FlowSpec {
            @Override
            public SourceSpan span() {
                return SourceSpan.UNKNOWN;
            }
        }

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.empty();
        FlowDefinition def = new FlowDefinition(
                1, "unsupported.flow", "1",
                new CustomUnregisteredSpec(),
                "test.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry);
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.UNSUPPORTED_SPEC, result.diagnostics().get(0).code());
    }

    @Test
    public void testEntryParallelInputTypeInferenceAndSuccess() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("branch.a", (OperationContext ctx, Order in) -> Outcome.accepted("A:" + in.id), Order.class, String.class)
                .operation("branch.b", (OperationContext ctx, Order in) -> Outcome.accepted("B:" + in.id), Order.class, String.class)
                .join("join.summary", results -> Outcome.accepted("JOINED"), String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "parallel.flow", "1",
                new ParallelSpec(
                        Arrays.asList(
                                new BranchSpec("b1", new StepSpec(SymbolRef.of("branch.a"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN),
                                new BranchSpec("b2", new StepSpec(SymbolRef.of("branch.b"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN)
                        ),
                        SymbolRef.of("join.summary"),
                        SourceSpan.UNKNOWN
                ),
                "test.flow", SourceSpan.UNKNOWN
        );

        // inferInitialInputType should narrow Order + Order -> Order
        TypeCheckResult result = TypeChecker.check(def, registry);
        Assert.assertTrue(result.success());
        Assert.assertEquals(TypeRef.of(String.class), result.outputType());
    }

    @Test
    public void testEntryParallelInputTypeMismatch() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("branch.order", (OperationContext ctx, Order in) -> Outcome.accepted("A"), Order.class, String.class)
                .operation("branch.payment", (OperationContext ctx, PaymentRequest in) -> Outcome.accepted("B"), PaymentRequest.class, String.class)
                .join("join.summary", results -> Outcome.accepted("JOINED"), String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "parallel.flow", "1",
                new ParallelSpec(
                        Arrays.asList(
                                new BranchSpec("b1", new StepSpec(SymbolRef.of("branch.order"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN),
                                new BranchSpec("b2", new StepSpec(SymbolRef.of("branch.payment"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN)
                        ),
                        SymbolRef.of("join.summary"),
                        SourceSpan.UNKNOWN
                ),
                "test.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry);
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.TYPE_MISMATCH, result.diagnostics().get(0).code());
    }

    @Test
    public void testEntryFirstApplicableInputTypeInferenceAndSuccess() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("fa.a", (OperationContext ctx, Order in) -> Outcome.accepted("A"), Order.class, String.class)
                .operation("fa.b", (OperationContext ctx, Order in) -> Outcome.accepted("B"), Order.class, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "fa.flow", "1",
                new FirstApplicableSpec(
                        Arrays.asList(
                                new StepSpec(SymbolRef.of("fa.a"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN),
                                new StepSpec(SymbolRef.of("fa.b"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN)
                        ),
                        SourceSpan.UNKNOWN
                ),
                "test.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry);
        Assert.assertTrue(result.success());
        Assert.assertEquals(TypeRef.of(String.class), result.outputType());
    }

    @Test
    public void testEntryFirstApplicableInputTypeMismatch() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("fa.order", (OperationContext ctx, Order in) -> Outcome.accepted("A"), Order.class, String.class)
                .operation("fa.payment", (OperationContext ctx, PaymentRequest in) -> Outcome.accepted("B"), PaymentRequest.class, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "fa.flow", "1",
                new FirstApplicableSpec(
                        Arrays.asList(
                                new StepSpec(SymbolRef.of("fa.order"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN),
                                new StepSpec(SymbolRef.of("fa.payment"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN)
                        ),
                        SourceSpan.UNKNOWN
                ),
                "test.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry);
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.TYPE_MISMATCH, result.diagnostics().get(0).code());
    }

    @Test
    public void testEmptyParallelThrowsDiagnostic() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .join("join.summary", results -> Outcome.accepted("JOINED"), String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "empty.parallel", "1",
                new ParallelSpec(Collections.emptyList(), SymbolRef.of("join.summary"), SourceSpan.UNKNOWN),
                "test.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry);
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.EMPTY_PARALLEL, result.diagnostics().get(0).code());
    }

    @Test
    public void testEmptyFirstApplicableThrowsDiagnostic() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.empty();
        FlowDefinition def = new FlowDefinition(
                1, "empty.fa", "1",
                new FirstApplicableSpec(Collections.emptyList(), SourceSpan.UNKNOWN),
                "test.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry);
        Assert.assertFalse(result.success());
        Assert.assertEquals(DiagnosticCodes.EMPTY_FIRST_APPLICABLE, result.diagnostics().get(0).code());
    }

    @Test
    public void testInvalidFlowDefinitionThrowsDiagnostic() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.empty();

        // 1. blank ID
        FlowDefinition badId = new FlowDefinition(
                1, "   ", "1",
                new CompleteSpec(CompleteSpec.CompleteKind.ACCEPTED, null, SourceSpan.UNKNOWN),
                "test.flow", SourceSpan.UNKNOWN
        );
        TypeCheckResult res1 = TypeChecker.check(badId, registry);
        Assert.assertFalse(res1.success());
        Assert.assertEquals(DiagnosticCodes.INVALID_FLOW_ID, res1.diagnostics().get(0).code());

        // 2. negative schema
        FlowDefinition badSchema = new FlowDefinition(
                -1, "valid.id", "1",
                new CompleteSpec(CompleteSpec.CompleteKind.ACCEPTED, null, SourceSpan.UNKNOWN),
                "test.flow", SourceSpan.UNKNOWN
        );
        TypeCheckResult res2 = TypeChecker.check(badSchema, registry);
        Assert.assertFalse(res2.success());
        Assert.assertEquals(DiagnosticCodes.DSL_UNSUPPORTED_SCHEMA, res2.diagnostics().get(0).code());
    }
}
