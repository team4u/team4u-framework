package com.team4u.framework.flow.definition.property;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Local;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.definition.binding.FlowBinder;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.definition.type.TypeRef;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.ParallelResults;
import com.team4u.framework.parser.SourceSpan;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

/**
 * 属性访问编译器与内置聚合策略测试。
 *
 * @author jay.wu
 */
public class DefaultPropertyAccessCompilerTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItem {
        private String sku;
        private int quantity;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Order {
        private String orderId;
        private OrderItem item;
        private String status;
        public String publicField;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderWithMapAttributes {
        private String orderId;
        private Map<String, Object> attributes;
    }

    public static class ReadOnlyBean {
        private final String code = "READ_ONLY";

        public String getCode() {
            return code;
        }
    }

    public static class WriteOnlyBean {
        private String secret;

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    public static class IntermediateWriteOnlyRoot {
        private WriteOnlyBean child;

        public void setChild(WriteOnlyBean child) {
            this.child = child;
        }
    }

    @Test
    public void testMapPropertyReadAndWrite() {
        DefaultPropertyAccessCompiler compiler = DefaultPropertyAccessCompiler.INSTANCE;

        // 读测试
        CompiledReader reader = compiler.compileReader(TypeRef.of(Map.class), PropertyPath.parse("$.data.order.id"));
        Map<String, Object> root = new HashMap<>();
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> order = new HashMap<>();
        order.put("id", "ORD-123");
        data.put("order", order);
        root.put("data", data);

        Object val = reader.read(root);
        Assert.assertEquals("ORD-123", val);

        // 写测试
        CompiledWriter writer = compiler.compileWriter(TypeRef.of(Map.class), PropertyPath.parse("$.data.order.status"), TypeRef.of(String.class));
        Object updated = writer.write(root, "PAID");
        Assert.assertSame(root, updated);
        Assert.assertEquals("PAID", order.get("status"));
    }

    @Test
    public void testMapPropertyNotFound() {
        DefaultPropertyAccessCompiler compiler = DefaultPropertyAccessCompiler.INSTANCE;
        CompiledReader reader = compiler.compileReader(TypeRef.of(Map.class), PropertyPath.parse("$.missing.key"));
        Map<String, Object> map = new HashMap<>();
        try {
            reader.read(map);
            Assert.fail("Expected PROPERTY_NOT_FOUND");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.PROPERTY_NOT_FOUND, ex.diagnostics().get(0).code());
        }
    }

    @Test
    public void testMapWriteIntermediateMissingThrowsPropertyNotFound() {
        DefaultPropertyAccessCompiler compiler = DefaultPropertyAccessCompiler.INSTANCE;
        CompiledWriter writer = compiler.compileWriter(TypeRef.of(Map.class), PropertyPath.parse("$.intermediate.leaf"), TypeRef.of(String.class));
        Map<String, Object> map = new HashMap<>();
        try {
            writer.write(map, "val");
            Assert.fail("Expected PROPERTY_NOT_FOUND");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.PROPERTY_NOT_FOUND, ex.diagnostics().get(0).code());
        }
    }

    @Test
    public void testMapWriteIntermediateNullThrowsPropertyNullValue() {
        DefaultPropertyAccessCompiler compiler = DefaultPropertyAccessCompiler.INSTANCE;
        CompiledWriter writer = compiler.compileWriter(TypeRef.of(Map.class), PropertyPath.parse("$.intermediate.leaf"), TypeRef.of(String.class));
        Map<String, Object> map = new HashMap<>();
        map.put("intermediate", null);
        try {
            writer.write(map, "val");
            Assert.fail("Expected PROPERTY_NULL_VALUE");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.PROPERTY_NULL_VALUE, ex.diagnostics().get(0).code());
        }
    }

    @Test
    public void testPojoPropertyReadAndWrite() {
        DefaultPropertyAccessCompiler compiler = DefaultPropertyAccessCompiler.INSTANCE;
        TypeRef orderType = TypeRef.of(Order.class);

        // 嵌套属性读取
        CompiledReader reader = compiler.compileReader(orderType, PropertyPath.parse("$.item.sku"));
        Assert.assertEquals(TypeRef.of(String.class), reader.resultType());

        Order order = new Order("O-1", new OrderItem("SKU-99", 5), "NEW", "initial");
        Assert.assertEquals("SKU-99", reader.read(order));

        // 公共字段写入
        CompiledWriter writerField = compiler.compileWriter(orderType, PropertyPath.parse("$.publicField"), TypeRef.of(String.class));
        writerField.write(order, "updatedField");
        Assert.assertEquals("updatedField", order.publicField);

        // Setter 写入
        CompiledWriter writerStatus = compiler.compileWriter(orderType, PropertyPath.parse("$.status"), TypeRef.of(String.class));
        writerStatus.write(order, "CONFIRMED");
        Assert.assertEquals("CONFIRMED", order.getStatus());
    }

    @Test
    public void testPojoUnknownProperty() {
        DefaultPropertyAccessCompiler compiler = DefaultPropertyAccessCompiler.INSTANCE;
        try {
            compiler.compileReader(TypeRef.of(Order.class), PropertyPath.parse("$.nonExistent"));
            Assert.fail("Expected UNKNOWN_PROPERTY");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.UNKNOWN_PROPERTY, ex.diagnostics().get(0).code());
        }
    }

    @Test
    public void testPojoReadOnlyPropertyNotWritable() {
        DefaultPropertyAccessCompiler compiler = DefaultPropertyAccessCompiler.INSTANCE;
        try {
            compiler.compileWriter(TypeRef.of(ReadOnlyBean.class), PropertyPath.parse("$.code"), TypeRef.of(String.class));
            Assert.fail("Expected PROPERTY_NOT_WRITABLE");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.PROPERTY_NOT_WRITABLE, ex.diagnostics().get(0).code());
        }
    }

    @Test
    public void testPojoWriteOnlyPropertyNotReadable() {
        DefaultPropertyAccessCompiler compiler = DefaultPropertyAccessCompiler.INSTANCE;
        try {
            compiler.compileReader(TypeRef.of(WriteOnlyBean.class), PropertyPath.parse("$.secret"));
            Assert.fail("Expected PROPERTY_NOT_READABLE");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.PROPERTY_NOT_READABLE, ex.diagnostics().get(0).code());
        }
    }

    @Test
    public void testNestedWriterIntermediateNotReadable() {
        DefaultPropertyAccessCompiler compiler = DefaultPropertyAccessCompiler.INSTANCE;
        try {
            compiler.compileWriter(TypeRef.of(IntermediateWriteOnlyRoot.class), PropertyPath.parse("$.child.secret"), TypeRef.of(String.class));
            Assert.fail("Expected PROPERTY_NOT_READABLE");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.PROPERTY_NOT_READABLE, ex.diagnostics().get(0).code());
        }
    }

    @Test
    public void testFinalPropertyNullYieldsPropertyNullValue() {
        DefaultPropertyAccessCompiler compiler = DefaultPropertyAccessCompiler.INSTANCE;
        CompiledReader reader = compiler.compileReader(TypeRef.of(Order.class), PropertyPath.parse("$.item"));
        Order order = new Order("O-3", null, "NEW", null);
        try {
            reader.read(order);
            Assert.fail("Expected PROPERTY_NULL_VALUE");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.PROPERTY_NULL_VALUE, ex.diagnostics().get(0).code());
        }
    }

    @Test
    public void testPojoTypeMismatch() {
        DefaultPropertyAccessCompiler compiler = DefaultPropertyAccessCompiler.INSTANCE;
        try {
            compiler.compileWriter(TypeRef.of(Order.class), PropertyPath.parse("$.status"), TypeRef.of(Integer.class));
            Assert.fail("Expected PROPERTY_TYPE_MISMATCH");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.PROPERTY_TYPE_MISMATCH, ex.diagnostics().get(0).code());
        }
    }

    @Test
    public void testPojoNullIntermediateProperty() {
        DefaultPropertyAccessCompiler compiler = DefaultPropertyAccessCompiler.INSTANCE;
        CompiledReader reader = compiler.compileReader(TypeRef.of(Order.class), PropertyPath.parse("$.item.sku"));
        Order order = new Order("O-2", null, "NEW", null);
        try {
            reader.read(order);
            Assert.fail("Expected PROPERTY_NULL_VALUE");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.PROPERTY_NULL_VALUE, ex.diagnostics().get(0).code());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testEndToEndStepWithPropertyProjectionAndMerge() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("inventory.reserve", (OperationContext ctx, OrderItem item) -> {
                    return Outcome.accepted("RES-" + item.getSku());
                }, OrderItem.class, String.class)
                .build();

        // Step: project $.item -> op -> merge $.status
        StepSpec step = new StepSpec(
                SymbolRef.of("inventory.reserve"),
                new PropertyProjectionSpec(PropertyPath.parse("$.item")),
                new PropertyMergeSpec(PropertyPath.parse("$.status")),
                Collections.emptyList(),
                SourceSpan.UNKNOWN
        );

        FlowDefinition def = new FlowDefinition(
                1, "order.reserve_flow", "1",
                step, "test.flow", SourceSpan.UNKNOWN
        );

        Flow<Object, Object> flow = (Flow<Object, Object>) FlowBinder.bind(def, registry, TypeRef.of(Order.class)).flow();

        Order order = new Order("O-100", new OrderItem("SKU-BOOK", 2), "PENDING", null);
        FlowResult result = Local.compile(flow).run(order);

        Assert.assertTrue(result instanceof FlowResult.Completed);
        Order completedOrder = (Order) ((Outcome.Accepted<Order>) ((FlowResult.Completed) result).outcome()).value();
        Assert.assertEquals("RES-SKU-BOOK", completedOrder.getStatus());
        Assert.assertEquals("O-100", completedOrder.getOrderId());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBuiltinParallelJoins() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("b1", (OperationContext ctx, String in) -> Outcome.accepted("RES_1"))
                .operation("b2", (OperationContext ctx, String in) -> Outcome.accepted("RES_2"))
                .build();

        // 1. join all
        ParallelSpec pAll = new ParallelSpec(
                Arrays.asList(
                        new BranchSpec("b1", new StepSpec(SymbolRef.of("b1"))),
                        new BranchSpec("b2", new StepSpec(SymbolRef.of("b2")))
                ),
                BuiltinJoinSpec.all(SourceSpan.UNKNOWN),
                SourceSpan.UNKNOWN
        );
        Flow<Object, Object> flowAll = (Flow<Object, Object>) FlowBinder.bind(
                new FlowDefinition(1, "p.all", "1", pAll, "test", SourceSpan.UNKNOWN),
                registry, TypeRef.of(String.class)).flow();
        FlowResult resAll = Local.compile(flowAll).run("input");
        Assert.assertTrue(resAll instanceof FlowResult.Completed);
        Assert.assertEquals("input", ((Outcome.Accepted<?>) ((FlowResult.Completed) resAll).outcome()).value());

        // 2. join first
        ParallelSpec pFirst = new ParallelSpec(
                Arrays.asList(
                        new BranchSpec("b1", new StepSpec(SymbolRef.of("b1"))),
                        new BranchSpec("b2", new StepSpec(SymbolRef.of("b2")))
                ),
                BuiltinJoinSpec.first(SourceSpan.UNKNOWN),
                SourceSpan.UNKNOWN
        );
        Flow<Object, Object> flowFirst = (Flow<Object, Object>) FlowBinder.bind(
                new FlowDefinition(1, "p.first", "1", pFirst, "test", SourceSpan.UNKNOWN),
                registry, TypeRef.of(String.class)).flow();
        FlowResult resFirst = Local.compile(flowFirst).run("input");
        Assert.assertTrue(resFirst instanceof FlowResult.Completed);
        Assert.assertTrue(((Outcome.Accepted<?>) ((FlowResult.Completed) resFirst).outcome()).value().toString().startsWith("RES_"));

        // 3. join collect
        ParallelSpec pCollect = new ParallelSpec(
                Arrays.asList(
                        new BranchSpec("b1", new StepSpec(SymbolRef.of("b1"))),
                        new BranchSpec("b2", new StepSpec(SymbolRef.of("b2")))
                ),
                BuiltinJoinSpec.collect(SourceSpan.UNKNOWN),
                SourceSpan.UNKNOWN
        );
        Flow<Object, Object> flowCollect = (Flow<Object, Object>) FlowBinder.bind(
                new FlowDefinition(1, "p.collect", "1", pCollect, "test", SourceSpan.UNKNOWN),
                registry, TypeRef.of(String.class)).flow();
        FlowResult resCollect = Local.compile(flowCollect).run("input");
        Assert.assertTrue(resCollect instanceof FlowResult.Completed);
        List<?> list = (List<?>) ((Outcome.Accepted<?>) ((FlowResult.Completed) resCollect).outcome()).value();
        Assert.assertEquals(2, list.size());

        // 4. join quorum
        ParallelSpec pQuorum = new ParallelSpec(
                Arrays.asList(
                        new BranchSpec("b1", new StepSpec(SymbolRef.of("b1"))),
                        new BranchSpec("b2", new StepSpec(SymbolRef.of("b2")))
                ),
                BuiltinJoinSpec.quorum(1, SourceSpan.UNKNOWN),
                SourceSpan.UNKNOWN
        );
        Flow<Object, Object> flowQuorum = (Flow<Object, Object>) FlowBinder.bind(
                new FlowDefinition(1, "p.quorum", "1", pQuorum, "test", SourceSpan.UNKNOWN),
                registry, TypeRef.of(String.class)).flow();
        FlowResult resQuorum = Local.compile(flowQuorum).run("input");
        Assert.assertTrue(resQuorum instanceof FlowResult.Completed);
        Assert.assertEquals("input", ((Outcome.Accepted<?>) ((FlowResult.Completed) resQuorum).outcome()).value());
    }

    @Test
    public void propertyPathInvariantsAndTrailingDot() {
        try {
            PropertyPath.parse("$.foo.");
            Assert.fail("Expected FlowDiagnosticException for trailing dot");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.INVALID_PROPERTY_PATH, ex.getDiagnostics().get(0).code());
        }

        try {
            new PropertyPath("$.foo", Collections.emptyList(), SourceSpan.UNKNOWN);
            Assert.fail("Expected IAE for empty segments");
        } catch (IllegalArgumentException expected) {
            // pass
        }

        try {
            new PropertyPath("$.foo", Arrays.asList("foo", ""), SourceSpan.UNKNOWN);
            Assert.fail("Expected IAE for empty segment");
        } catch (IllegalArgumentException expected) {
            // pass
        }

        try {
            new PropertyPath("$.foo", Collections.singletonList("bar"), SourceSpan.UNKNOWN);
            Assert.fail("Expected IAE for expression mismatch with segments");
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }

    @Test
    public void pojoToMapDynamicTailReaderAndWriter() {
        DefaultPropertyAccessCompiler compiler = new DefaultPropertyAccessCompiler();
        Map<String, Object> attrs = new HashMap<String, Object>();
        attrs.put("userId", "u-100");
        OrderWithMapAttributes order = new OrderWithMapAttributes("order-1", attrs);

        // Reader
        CompiledReader reader = compiler.compileReader(
                TypeRef.of(OrderWithMapAttributes.class), PropertyPath.parse("$.attributes.userId"));
        Assert.assertEquals(TypeRef.ANY, reader.resultType());
        Assert.assertEquals("u-100", reader.read(order));

        // Writer
        CompiledWriter writer = compiler.compileWriter(
                TypeRef.of(OrderWithMapAttributes.class), PropertyPath.parse("$.attributes.userId"), TypeRef.of(String.class));
        Assert.assertEquals(TypeRef.of(OrderWithMapAttributes.class), writer.resultType());
        OrderWithMapAttributes updated = (OrderWithMapAttributes) writer.write(order, "u-200");
        Assert.assertSame(order, updated);
        Assert.assertEquals("u-200", order.getAttributes().get("userId"));
    }

    @Test
    public void parallelHeterogeneousBranchesJoinAllAndQuorumAndCustomJoin() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("strOp", (OperationContext ctx, Object in) -> Outcome.accepted("STR"), Object.class, String.class)
                .operation("intOp", (OperationContext ctx, Object in) -> Outcome.accepted(123), Object.class, Integer.class)
                .join("customJoin", results -> Outcome.accepted(99.9), Double.class)
                .build();

        // 1. join all with heterogeneous branches (String + Integer) -> should bind successfully!
        ParallelSpec pAll = new ParallelSpec(
                Arrays.asList(
                        new BranchSpec("b1", new StepSpec(SymbolRef.of("strOp"))),
                        new BranchSpec("b2", new StepSpec(SymbolRef.of("intOp")))
                ),
                BuiltinJoinSpec.all(SourceSpan.UNKNOWN),
                SourceSpan.UNKNOWN
        );
        com.team4u.framework.flow.definition.binding.BoundFlow boundAll = FlowBinder.bind(
                new FlowDefinition(1, "p.all.hetero", "1", pAll, "test", SourceSpan.UNKNOWN),
                registry, TypeRef.of(String.class));
        Assert.assertEquals(TypeRef.of(String.class), boundAll.outputType());

        // 2. join quorum with heterogeneous branches -> should bind successfully!
        ParallelSpec pQuorum = new ParallelSpec(
                Arrays.asList(
                        new BranchSpec("b1", new StepSpec(SymbolRef.of("strOp"))),
                        new BranchSpec("b2", new StepSpec(SymbolRef.of("intOp")))
                ),
                BuiltinJoinSpec.quorum(1, SourceSpan.UNKNOWN),
                SourceSpan.UNKNOWN
        );
        com.team4u.framework.flow.definition.binding.BoundFlow boundQuorum = FlowBinder.bind(
                new FlowDefinition(1, "p.quorum.hetero", "1", pQuorum, "test", SourceSpan.UNKNOWN),
                registry, TypeRef.of(String.class));
        Assert.assertEquals(TypeRef.of(String.class), boundQuorum.outputType());

        // 3. custom join with heterogeneous branches -> should bind successfully!
        ParallelSpec pCustom = new ParallelSpec(
                Arrays.asList(
                        new BranchSpec("b1", new StepSpec(SymbolRef.of("strOp"))),
                        new BranchSpec("b2", new StepSpec(SymbolRef.of("intOp")))
                ),
                SymbolRef.of("customJoin"),
                SourceSpan.UNKNOWN
        );
        com.team4u.framework.flow.definition.binding.BoundFlow boundCustom = FlowBinder.bind(
                new FlowDefinition(1, "p.custom.hetero", "1", pCustom, "test", SourceSpan.UNKNOWN),
                registry, TypeRef.of(String.class));
        Assert.assertEquals(TypeRef.of(Double.class), boundCustom.outputType());
    }

    @Test
    public void parallelJoinFirstWithAnyDoesNotNarrowToConcreteType() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("anyOp", (OperationContext ctx, Object in) -> Outcome.accepted("val"))
                .operation("strOp", (OperationContext ctx, Object in) -> Outcome.accepted("val"), Object.class, String.class)
                .build();

        ParallelSpec pFirst = new ParallelSpec(
                Arrays.asList(
                        new BranchSpec("b1", new StepSpec(SymbolRef.of("anyOp"))),
                        new BranchSpec("b2", new StepSpec(SymbolRef.of("strOp")))
                ),
                BuiltinJoinSpec.first(SourceSpan.UNKNOWN),
                SourceSpan.UNKNOWN
        );
        com.team4u.framework.flow.definition.binding.BoundFlow boundFirst = FlowBinder.bind(
                new FlowDefinition(1, "p.first.any", "1", pFirst, "test", SourceSpan.UNKNOWN),
                registry, TypeRef.of(String.class));
        // MUST be ANY, NOT narrowed to String!
        Assert.assertEquals(TypeRef.ANY, boundFirst.outputType());
    }
}
