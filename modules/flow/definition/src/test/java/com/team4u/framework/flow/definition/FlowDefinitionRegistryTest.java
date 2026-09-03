package com.team4u.framework.flow.definition;

import com.team4u.framework.parser.SourceSpan;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.LocalExecutable;
import com.team4u.framework.flow.api.*;
import com.team4u.framework.flow.definition.binding.BoundFlow;
import com.team4u.framework.flow.definition.binding.FlowBinder;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.definition.registry.OperationDescriptor;
import com.team4u.framework.flow.definition.registry.PolicyDescriptor;
import com.team4u.framework.flow.definition.type.TypeCheckResult;
import com.team4u.framework.flow.definition.type.TypeChecker;
import com.team4u.framework.flow.definition.type.TypeRef;
import com.team4u.framework.flow.model.FlowResult;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.spi.OperationResolver;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class FlowDefinitionRegistryTest {

    static class OrderReq {
        String orderId;
    }

    static class OrderResp {
        boolean valid;
    }

    static class ValidateOp implements Operation<OrderReq, OrderResp> {
        @Override
        public Outcome<OrderResp> execute(OperationContext context, OrderReq input) {
            OrderResp resp = new OrderResp();
            resp.valid = input != null && input.orderId != null;
            return Outcome.accepted(resp);
        }
    }

    static class ChargeOp implements Operation<OrderResp, String> {
        @Override
        public Outcome<String> execute(OperationContext context, OrderResp input) {
            return Outcome.accepted("PAID");
        }
    }

    static class OrderPolicy implements Policy<OrderReq> {
        @Override
        public Gate before(PolicyContext context, OrderReq key) {
            return Gate.proceed();
        }
    }

    @Test
    public void schemeAGenericTypeInferenceOnRegistryBuilder() {
        ValidateOp validateOp = new ValidateOp();

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.validate", validateOp)
                .operation("order.charge", ChargeOp.class)
                .operation("order.charge.qualified", ChargeOp.class, "chargeBean")
                .policy("order.policy", new OrderPolicy())
                .policy("order.policy.contract", OrderPolicy.class)
                .build();

        OperationDescriptor op1 = registry.operation("order.validate");
        Assert.assertNotNull(op1);
        Assert.assertEquals(TypeRef.of(OrderReq.class), op1.inputType());
        Assert.assertEquals(TypeRef.of(OrderResp.class), op1.outputType());
        Assert.assertSame(validateOp, op1.instance());

        OperationDescriptor op2 = registry.operation("order.charge");
        Assert.assertNotNull(op2);
        Assert.assertEquals(TypeRef.of(OrderResp.class), op2.inputType());
        Assert.assertEquals(TypeRef.of(String.class), op2.outputType());
        Assert.assertEquals(ChargeOp.class, op2.contract());

        OperationDescriptor op3 = registry.operation("order.charge.qualified");
        Assert.assertNotNull(op3);
        Assert.assertEquals(TypeRef.of(OrderResp.class), op3.inputType());
        Assert.assertEquals(TypeRef.of(String.class), op3.outputType());
        Assert.assertEquals("chargeBean", op3.qualifier());

        PolicyDescriptor policy1 = registry.policy("order.policy");
        Assert.assertNotNull(policy1);
        Assert.assertEquals(TypeRef.of(OrderReq.class), policy1.keyType());

        PolicyDescriptor policy2 = registry.policy("order.policy.contract");
        Assert.assertNotNull(policy2);
        Assert.assertEquals(TypeRef.of(OrderReq.class), policy2.keyType());
    }

    @Test
    public void schemeAStaticTypeCheckWithoutExplicitClasses() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.validate", new ValidateOp())
                .operation("order.charge", ChargeOp.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "order.flow", "1",
                new SequenceSpec(Arrays.<FlowSpec>asList(
                        new StepSpec(SymbolRef.of("order.validate"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN),
                        new StepSpec(SymbolRef.of("order.charge"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN)
                ), SourceSpan.UNKNOWN),
                "order.flow", SourceSpan.UNKNOWN
        );

        TypeCheckResult result = TypeChecker.check(def, registry);
        Assert.assertTrue(result.success());
        Assert.assertEquals(TypeRef.of(OrderReq.class), result.inputType());
        Assert.assertEquals(TypeRef.of(String.class), result.outputType());
    }

    @Test
    public void schemeCConventionFallbackDiscovery() {
        final Map<String, Object> container = new HashMap<String, Object>();
        ValidateOp validateOp = new ValidateOp();
        OrderPolicy orderPolicy = new OrderPolicy();
        container.put("order.validate", validateOp);
        container.put("order.policy", orderPolicy);

        OperationResolver customResolver = new OperationResolver() {
            @Override
            public Object resolve(String identifier) {
                return container.get(identifier);
            }

            @Override
            public Object resolve(Class<?> contract, String qualifier) {
                return qualifier != null ? container.get(qualifier) : null;
            }
        };

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .fallbackResolver(customResolver)
                .build();

        // 注册表未显式注册 "order.validate" 与 "order.policy"，通过 Convention 回退解析
        OperationDescriptor opDesc = registry.operation("order.validate");
        Assert.assertNotNull(opDesc);
        Assert.assertSame(validateOp, opDesc.instance());
        Assert.assertEquals(TypeRef.of(OrderReq.class), opDesc.inputType());
        Assert.assertEquals(TypeRef.of(OrderResp.class), opDesc.outputType());

        // 验证只读安全映射的缓存一致性
        OperationDescriptor cachedDesc = registry.operation("order.validate");
        Assert.assertSame(opDesc, cachedDesc);

        PolicyDescriptor policyDesc = registry.policy("order.policy");
        Assert.assertNotNull(policyDesc);
        Assert.assertSame(orderPolicy, policyDesc.instance());
        Assert.assertEquals(TypeRef.of(OrderReq.class), policyDesc.keyType());

        // 未知符号安全返回 null
        Assert.assertNull(registry.operation("unknown.operation"));
        Assert.assertNull(registry.policy("unknown.policy"));
    }

    @Test
    public void schemeCConventionEndToEndBinding() {
        final Map<String, Object> container = new HashMap<String, Object>();
        container.put("order.validate", new ValidateOp());
        container.put("order.charge", new ChargeOp());

        OperationResolver customResolver = new OperationResolver() {
            @Override
            public Object resolve(String identifier) {
                return container.get(identifier);
            }

            @Override
            public Object resolve(Class<?> contract, String qualifier) {
                return qualifier != null ? container.get(qualifier) : null;
            }
        };

        // 仅配置 fallbackResolver，不手动注册任何 Operation
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .fallbackResolver(customResolver)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "order.flow", "1",
                new SequenceSpec(Arrays.<FlowSpec>asList(
                        new StepSpec(SymbolRef.of("order.validate"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN),
                        new StepSpec(SymbolRef.of("order.charge"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN)
                ), SourceSpan.UNKNOWN),
                "order.flow", SourceSpan.UNKNOWN
        );

        BoundFlow bound = FlowBinder.bind(def, registry);
        Assert.assertNotNull(bound);
        Assert.assertEquals(TypeRef.of(OrderReq.class), bound.inputType());
        Assert.assertEquals(TypeRef.of(String.class), bound.outputType());
    }

    @Test
    public void testDuplicateRegistrationThrowsIllegalArgumentException() {
        ValidateOp op1 = new ValidateOp();
        ValidateOp op2 = new ValidateOp();

        try {
            FlowDefinitionRegistry.builder()
                    .operation("order.validate", op1)
                    .operation("order.validate", op2)
                    .build();
            Assert.fail("Expected IllegalArgumentException for duplicate operation registration");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("Duplicate operation registration"));
        }
    }

    @Test
    public void testExplicitOverrideReplacesRegistration() {
        ValidateOp op1 = new ValidateOp();
        ValidateOp op2 = new ValidateOp();

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .operation("order.validate", op1)
                .overrideOperation("order.validate", op2)
                .build();

        Assert.assertSame(op2, registry.operation("order.validate").instance());
    }

    @Test
    public void testJoinProviderRegistrationAndIoCResolution() {
        com.team4u.framework.flow.api.JoinStrategy<String> dynamicJoin = results -> Outcome.accepted("RESOLVED_BY_PROVIDER");

        com.team4u.framework.flow.definition.registry.JoinProvider joinProvider = new com.team4u.framework.flow.definition.registry.JoinProvider() {
            @Override
            public com.team4u.framework.flow.definition.registry.JoinDescriptor descriptor() {
                return com.team4u.framework.flow.definition.registry.JoinDescriptor.builder()
                        .id("dynamic.join")
                        .outputType(TypeRef.of(String.class))
                        .build();
            }

            @Override
            public com.team4u.framework.flow.api.JoinStrategy<?> provide(OperationResolver resolver) {
                return dynamicJoin;
            }
        };

        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .join(joinProvider)
                .operation("step1", (OperationContext ctx, String in) -> Outcome.accepted(in), String.class, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "parallel.provider", "1",
                new ParallelSpec(
                        Collections.singletonList(
                                new BranchSpec("b1", new StepSpec(SymbolRef.of("step1"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN)
                        ),
                        SymbolRef.of("dynamic.join"),
                        SourceSpan.UNKNOWN
                ),
                "test.flow", SourceSpan.UNKNOWN
        );

        BoundFlow bound = FlowBinder.bind(def, registry);
        LocalExecutable<String, String> exec = bound.compileLocal(String.class, String.class);
        FlowResult<String> result = exec.run("data");
        Assert.assertEquals("RESOLVED_BY_PROVIDER", result.requireAccepted());
    }

    public interface MyContractJoin extends com.team4u.framework.flow.api.JoinStrategy<String> { }

    @Test
    public void testClassBoundJoinDeferredIoCResolution() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .join("contract.join", MyContractJoin.class, String.class)
                .operation("step1", (OperationContext ctx, String in) -> Outcome.accepted(in), String.class, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "parallel.contract", "1",
                new ParallelSpec(
                        Collections.singletonList(
                                new BranchSpec("b1", new StepSpec(SymbolRef.of("step1"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN)
                        ),
                        SymbolRef.of("contract.join"),
                        SourceSpan.UNKNOWN
                ),
                "test.flow", SourceSpan.UNKNOWN
        );

        // 绑定期完全不需要 Resolver
        BoundFlow bound = FlowBinder.bind(def, registry);

        // 运行期通过不同的 Resolver 解析各自的 Join 实例
        OperationResolver resolverA = (contract, qualifier) ->
                (MyContractJoin) results -> Outcome.accepted("JOINED_A");
        LocalExecutable<String, String> execA = bound.compileLocal(String.class, String.class, resolverA);
        Assert.assertEquals("JOINED_A", execA.run("data").requireAccepted());

        OperationResolver resolverB = (contract, qualifier) ->
                (MyContractJoin) results -> Outcome.accepted("JOINED_B");
        LocalExecutable<String, String> execB = bound.compileLocal(String.class, String.class, resolverB);
        Assert.assertEquals("JOINED_B", execB.run("data").requireAccepted());
    }

    @Test
    public void testClassBoundJoinResolverFailureFailsClosed() {
        FlowDefinitionRegistry registry = FlowDefinitionRegistry.builder()
                .join("contract.join", MyContractJoin.class, String.class)
                .operation("step1", (OperationContext ctx, String in) -> Outcome.accepted(in), String.class, String.class)
                .build();

        FlowDefinition def = new FlowDefinition(
                1, "parallel.contract.fail", "1",
                new ParallelSpec(
                        Collections.singletonList(
                                new BranchSpec("b1", new StepSpec(SymbolRef.of("step1"), null, null, Collections.emptyList(), SourceSpan.UNKNOWN), SourceSpan.UNKNOWN)
                        ),
                        SymbolRef.of("contract.join"),
                        SourceSpan.UNKNOWN
                ),
                "test.flow", SourceSpan.UNKNOWN
        );

        BoundFlow bound = FlowBinder.bind(def, registry);

        // Resolver 无法解析时严格 fail-closed，严禁静默反射 newInstance
        OperationResolver failingResolver = (contract, qualifier) -> null;
        try {
            bound.compileLocal(String.class, String.class, failingResolver);
            Assert.fail("Expected FlowBuildException when join cannot be resolved");
        } catch (com.team4u.framework.flow.model.FlowBuildException ex) {
            Assert.assertEquals("MISSING_BINDING", ex.problems().get(0).code());
        }
    }
}
