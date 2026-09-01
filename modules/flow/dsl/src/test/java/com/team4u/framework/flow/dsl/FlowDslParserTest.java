package com.team4u.framework.flow.dsl;

import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.dsl.parser.FlowDslParser;
import org.junit.Assert;
import org.junit.Test;

public class FlowDslParserTest {

    @Test
    public void testParseFullSpecificationDsl() {
        String dsl = "schema 1\n" +
                "\n" +
                "flow order.create version 7 {\n" +
                "\n" +
                "    step order.validate\n" +
                "\n" +
                "    step inventory.reserve {\n" +
                "        project order.items\n" +
                "        merge order.withReservation\n" +
                "        policy inventory.rate-limit\n" +
                "        timeout 1s\n" +
                "    }\n" +
                "\n" +
                "    step payment.charge {\n" +
                "        policy payment.rate-limit {\n" +
                "            key order.userId\n" +
                "        }\n" +
                "        retry payment.standard\n" +
                "        timeout 5s\n" +
                "    }\n" +
                "\n" +
                "    route order.status {\n" +
                "        case PAID {\n" +
                "            step order.confirm\n" +
                "        }\n" +
                "        case CANCELLED {\n" +
                "            step order.cancel\n" +
                "        }\n" +
                "        otherwise {\n" +
                "            skipped \"NO_ROUTE\"\n" +
                "        }\n" +
                "    }\n" +
                "\n" +
                "    parallel {\n" +
                "        branch risk {\n" +
                "            step risk.check\n" +
                "        }\n" +
                "        branch inventory {\n" +
                "            step inventory.verify\n" +
                "        }\n" +
                "        join order.validation\n" +
                "    }\n" +
                "\n" +
                "    await payment.callback\n" +
                "\n" +
                "    step order.finish\n" +
                "}\n";

        FlowDefinition def = FlowDslParser.parse(dsl, "order.flow");
        Assert.assertEquals(1, def.schema());
        Assert.assertEquals("order.create", def.id());
        Assert.assertEquals("7", def.version());
        Assert.assertTrue(def.root() instanceof SequenceSpec);

        SequenceSpec seq = (SequenceSpec) def.root();
        Assert.assertEquals(7, seq.elements().size());

        // 1. step order.validate
        Assert.assertTrue(seq.elements().get(0) instanceof StepSpec);
        StepSpec step1 = (StepSpec) seq.elements().get(0);
        Assert.assertEquals("order.validate", step1.operation().id());

        // 2. step inventory.reserve with project, merge, policy, timeout
        Assert.assertTrue(seq.elements().get(1) instanceof StepSpec);
        StepSpec step2 = (StepSpec) seq.elements().get(1);
        Assert.assertEquals("inventory.reserve", step2.operation().id());
        Assert.assertEquals("order.items", step2.project().id());
        Assert.assertEquals("order.withReservation", step2.merge().id());
        Assert.assertEquals(2, step2.modifiers().size());

        // 3. step payment.charge with policy block, retry, timeout
        Assert.assertTrue(seq.elements().get(2) instanceof StepSpec);
        StepSpec step3 = (StepSpec) seq.elements().get(2);
        Assert.assertEquals("payment.charge", step3.operation().id());
        Assert.assertEquals(3, step3.modifiers().size());

        // 4. route order.status
        Assert.assertTrue(seq.elements().get(3) instanceof RouteSpec);
        RouteSpec route = (RouteSpec) seq.elements().get(3);
        Assert.assertEquals("order.status", route.selector().id());
        Assert.assertEquals(2, route.cases().size());
        Assert.assertNotNull(route.otherwise());

        // 5. parallel
        Assert.assertTrue(seq.elements().get(4) instanceof ParallelSpec);
        ParallelSpec parallel = (ParallelSpec) seq.elements().get(4);
        Assert.assertEquals(2, parallel.branches().size());
        Assert.assertEquals("order.validation", parallel.join().id());

        // 6. await
        Assert.assertTrue(seq.elements().get(5) instanceof AwaitSpec);
        Assert.assertEquals("payment.callback", ((AwaitSpec) seq.elements().get(5)).resumePoint().id());

        // 7. step order.finish
        Assert.assertTrue(seq.elements().get(6) instanceof StepSpec);
        Assert.assertEquals("order.finish", ((StepSpec) seq.elements().get(6)).operation().id());
    }

    @Test
    public void testParseFirstApplicableAndRecover() {
        String dsl = "flow fallback.demo {\n" +
                "    firstApplicable {\n" +
                "        step cache.find\n" +
                "        step database.find\n" +
                "    }\n" +
                "    recover {\n" +
                "        body {\n" +
                "            step payment.charge\n" +
                "        }\n" +
                "        onFailure {\n" +
                "            step payment.compensate\n" +
                "        }\n" +
                "    }\n" +
                "}";

        FlowDefinition def = FlowDslParser.parse(dsl);
        SequenceSpec seq = (SequenceSpec) def.root();
        Assert.assertEquals(2, seq.elements().size());

        Assert.assertTrue(seq.elements().get(0) instanceof FirstApplicableSpec);
        FirstApplicableSpec fa = (FirstApplicableSpec) seq.elements().get(0);
        Assert.assertEquals(2, fa.branches().size());

        Assert.assertTrue(seq.elements().get(1) instanceof RecoverSpec);
        RecoverSpec rec = (RecoverSpec) seq.elements().get(1);
        Assert.assertNotNull(rec.body());
        Assert.assertNotNull(rec.onFailure());
    }

    @Test
    public void testParseScopes() {
        String dsl = "flow scope.demo {\n" +
                "    timeout 10s {\n" +
                "        step step1\n" +
                "        step step2\n" +
                "    }\n" +
                "    scope \"transaction\" {\n" +
                "        step step3\n" +
                "    }\n" +
                "}";

        FlowDefinition def = FlowDslParser.parse(dsl);
        SequenceSpec seq = (SequenceSpec) def.root();
        Assert.assertEquals(2, seq.elements().size());

        Assert.assertTrue(seq.elements().get(0) instanceof ControlSpec);
        ControlSpec timeoutCtrl = (ControlSpec) seq.elements().get(0);
        Assert.assertEquals(ControlSpec.ControlKind.TIMEOUT, timeoutCtrl.kind());

        Assert.assertTrue(seq.elements().get(1) instanceof ControlSpec);
        ControlSpec scopeCtrl = (ControlSpec) seq.elements().get(1);
        Assert.assertEquals(ControlSpec.ControlKind.SCOPE, scopeCtrl.kind());
    }

    @Test
    public void testSyntaxErrorThrowsDiagnosticException() {
        String dsl = "flow invalid.syntax { step }";
        try {
            FlowDslParser.parse(dsl, "bad.flow");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertFalse(ex.getDiagnostics().isEmpty());
            Assert.assertTrue(ex.getMessage().contains("bad.flow"));
        }
    }

    @Test
    public void testDuplicateProjectThrowsDiagnostic() {
        String dsl = "flow test {\n" +
                "    step op {\n" +
                "        project p1\n" +
                "        project p2\n" +
                "    }\n" +
                "}";
        try {
            FlowDslParser.parse(dsl, "test.flow");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(1, ex.getDiagnostics().size());
            Assert.assertEquals("DUPLICATE_STEP_PROJECT", ex.getDiagnostics().get(0).code());
        }
    }

    @Test
    public void testDuplicateMergeThrowsDiagnostic() {
        String dsl = "flow test {\n" +
                "    step op {\n" +
                "        merge m1\n" +
                "        merge m2\n" +
                "    }\n" +
                "}";
        try {
            FlowDslParser.parse(dsl, "test.flow");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(1, ex.getDiagnostics().size());
            Assert.assertEquals("DUPLICATE_STEP_MERGE", ex.getDiagnostics().get(0).code());
        }
    }

    @Test
    public void testContentAfterEOFThrowsDiagnostic() {
        String dsl = "flow test {\n" +
                "    step op\n" +
                "} extra_content_here";
        try {
            FlowDslParser.parse(dsl, "test.flow");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(1, ex.getDiagnostics().size());
            Assert.assertEquals(com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes.DSL_SYNTAX_ERROR, ex.getDiagnostics().get(0).code());
            Assert.assertTrue(ex.getMessage().contains("Unexpected content after flow definition"));
        }
    }

    @Test
    public void testDuplicateOtherwiseThrowsDiagnostic() {
        String dsl = "flow test {\n" +
                "    route status {\n" +
                "        case PAID { step op1 }\n" +
                "        otherwise { step op2 }\n" +
                "        otherwise { step op3 }\n" +
                "    }\n" +
                "}";
        try {
            FlowDslParser.parse(dsl, "test.flow");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(1, ex.getDiagnostics().size());
            Assert.assertEquals(com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes.DUPLICATE_OTHERWISE, ex.getDiagnostics().get(0).code());
        }
    }

    @Test
    public void testDuplicateJoinThrowsDiagnostic() {
        String dsl = "flow test {\n" +
                "    parallel {\n" +
                "        branch b1 { step op1 }\n" +
                "        join j1\n" +
                "        join j2\n" +
                "    }\n" +
                "}";
        try {
            FlowDslParser.parse(dsl, "test.flow");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(1, ex.getDiagnostics().size());
            Assert.assertEquals(com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes.DUPLICATE_JOIN, ex.getDiagnostics().get(0).code());
        }
    }

    @Test
    public void testDuplicateConfigKeyThrowsDiagnostic() {
        String dsl = "flow test {\n" +
                "    step op {\n" +
                "        policy rate.limit {\n" +
                "            limit = 10,\n" +
                "            limit = 20\n" +
                "        }\n" +
                "    }\n" +
                "}";
        try {
            FlowDslParser.parse(dsl, "test.flow");
            Assert.fail("Expected FlowDiagnosticException");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(1, ex.getDiagnostics().size());
            Assert.assertEquals(com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes.DUPLICATE_CONFIG_KEY, ex.getDiagnostics().get(0).code());
        }
    }
}
