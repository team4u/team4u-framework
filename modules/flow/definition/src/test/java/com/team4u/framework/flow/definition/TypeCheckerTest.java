package com.team4u.framework.flow.definition;

import com.team4u.framework.flow.api.OperationContext;
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
}
