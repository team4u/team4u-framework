package com.team4u.framework.flow.definition;

import com.team4u.framework.flow.definition.binding.BindingContext;
import com.team4u.framework.flow.definition.diagnostic.Diagnostic;
import com.team4u.framework.flow.definition.diagnostic.DiagnosticCodes;
import com.team4u.framework.flow.definition.diagnostic.FlowDiagnosticException;
import com.team4u.framework.flow.definition.model.*;
import com.team4u.framework.flow.definition.registry.FlowDefinitionRegistry;
import com.team4u.framework.flow.definition.type.TypeRef;
import com.team4u.framework.flow.definition.validation.FlowDefinitionValidator;
import com.team4u.framework.parser.SourceSpan;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FlowDefinitionValidatorTest {

    @Test
    public void testSchema1And2Supported() {
        StepSpec step1 = new StepSpec(SymbolRef.of("op1"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN);
        FlowDefinition def1 = new FlowDefinition(1, "flow1", "1", step1, "flow1.flow", SourceSpan.UNKNOWN);
        List<Diagnostic> d1 = FlowDefinitionValidator.validate(def1);
        Assert.assertTrue(d1.isEmpty());

        StepSpec step2 = new StepSpec(SymbolRef.of("op2"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN);
        FlowDefinition def2 = new FlowDefinition(2, "flow2", "1", step2, "flow2.flow", SourceSpan.UNKNOWN);
        List<Diagnostic> d2 = FlowDefinitionValidator.validate(def2);
        Assert.assertTrue(d2.isEmpty());

        StepSpec step3 = new StepSpec(SymbolRef.of("op3"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN);
        FlowDefinition def3 = new FlowDefinition(3, "flow3", "1", step3, "flow3.flow", SourceSpan.UNKNOWN);
        List<Diagnostic> d3 = FlowDefinitionValidator.validate(def3);
        Assert.assertFalse(d3.isEmpty());
        Assert.assertEquals(DiagnosticCodes.DSL_UNSUPPORTED_SCHEMA, d3.get(0).code());
    }

    @Test
    public void testDuplicateAstNodeInstanceRejected() {
        StepSpec sharedStep = new StepSpec(SymbolRef.of("sharedOp"), null, null, Collections.<ModifierSpec>emptyList(), SourceSpan.UNKNOWN);
        SequenceSpec seq = new SequenceSpec(Arrays.asList(sharedStep, sharedStep), SourceSpan.UNKNOWN);

        FlowDefinition def = new FlowDefinition(2, "flow.dup", "1", seq, "dup.flow", SourceSpan.UNKNOWN);
        List<Diagnostic> diagnostics = FlowDefinitionValidator.validate(def);

        Assert.assertFalse(diagnostics.isEmpty());
        boolean hasDuplicateError = false;
        for (Diagnostic d : diagnostics) {
            if (DiagnosticCodes.INVALID_DEFINITION.equals(d.code()) && d.message().contains("Duplicate AST node detected")) {
                hasDuplicateError = true;
                break;
            }
        }
        Assert.assertTrue("Should detect duplicate AST node instance", hasDuplicateError);
    }

    @Test
    public void testUnsupportedProjectionAndMergeSpecFailClosed() {
        BindingContext context = new BindingContext() {
            @Override
            public FlowDefinitionRegistry registry() {
                return FlowDefinitionRegistry.builder().build();
            }

            @Override
            public com.team4u.framework.flow.spi.OperationResolver resolver() {
                return null;
            }

            @Override
            public TypeRef currentType() {
                return TypeRef.ANY;
            }

            @Override
            public TypeRef inputTypeOf(FlowSpec spec) {
                return TypeRef.ANY;
            }

            @Override
            public TypeRef outputTypeOf(FlowSpec spec) {
                return TypeRef.ANY;
            }

            @Override
            public com.team4u.framework.flow.Flow<?, ?> bindSpec(FlowSpec spec) {
                return null;
            }

            @Override
            public com.team4u.framework.flow.Flow<?, ?> applyPolicy(
                    com.team4u.framework.flow.Flow<?, ?> flow,
                    String policyId,
                    SymbolRef keyRef,
                    java.util.Map<String, Object> configuration) {
                return null;
            }
        };

        ProjectionSpec unknownProj = new ProjectionSpec() {
            @Override
            public SourceSpan span() {
                return SourceSpan.UNKNOWN;
            }
        };

        try {
            context.compileProjector(unknownProj);
            Assert.fail("Expected UNSUPPORTED_PROJECTION_SPEC");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.UNSUPPORTED_PROJECTION_SPEC, ex.diagnostics().get(0).code());
        }

        MergeSpec unknownMerge = new MergeSpec() {
            @Override
            public SourceSpan span() {
                return SourceSpan.UNKNOWN;
            }
        };

        try {
            context.compileMerger(unknownMerge, TypeRef.ANY, TypeRef.ANY);
            Assert.fail("Expected UNSUPPORTED_MERGE_SPEC");
        } catch (FlowDiagnosticException ex) {
            Assert.assertEquals(DiagnosticCodes.UNSUPPORTED_MERGE_SPEC, ex.diagnostics().get(0).code());
        }
    }
}
