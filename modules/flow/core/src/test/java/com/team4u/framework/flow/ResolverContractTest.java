package com.team4u.framework.flow;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 守护 Compiler.resolve 对 resolver 返回对象的具体 contract 校验：
 * resolver 返回的对象实现了 marker（Operation）但不实现 class 绑定请求的具体 contract 子接口时，
 * 编译期必须报 BINDING_TYPE；同时不得对正确实现或实例绑定（lambda/普通类）误报。
 */
public class ResolverContractTest {

    @Test
    public void resolverReturningOnlyMarkerImplementationIsRejected() {
        OperationResolver resolver = (contract, qualifier) -> new PlainOperation();
        try {
            Local.compile(Flow.step(SpecificOp.class), resolver);
            fail("Expected FlowBuildException");
        } catch (FlowBuildException error) {
            assertTrue(error.problems().stream()
                    .anyMatch(problem -> problem.code().equals("BINDING_TYPE")));
        }
    }

    @Test
    public void resolverReturningExactContractCompiles() {
        OperationResolver resolver = (contract, qualifier) -> new SpecificOperation();
        assertEquals("ok", Local.compile(Flow.step(SpecificOp.class), resolver)
                .run("input").requireAccepted());
    }

    @Test
    public void instanceBindingsAreNotAffected() {
        assertEquals("LAMBDA", Local.compile(Flow.step(
                (Operation<String, String>) (context, input) -> Outcome.accepted("LAMBDA")))
                .run("input").requireAccepted());
        assertEquals("PLAIN", Local.compile(Flow.step(new PlainOperation()))
                .run("input").requireAccepted());
    }

    interface SpecificOp extends Operation<String, String> { }

    static final class SpecificOperation implements SpecificOp {
        @Override public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted("ok");
        }
    }

    static final class PlainOperation implements Operation<String, String> {
        @Override public Outcome<String> execute(OperationContext context, String input) {
            return Outcome.accepted("PLAIN");
        }
    }
}
