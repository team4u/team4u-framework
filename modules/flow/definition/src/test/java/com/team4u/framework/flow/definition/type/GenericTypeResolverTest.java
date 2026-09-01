package com.team4u.framework.flow.definition.type;

import com.team4u.framework.flow.api.Gate;
import com.team4u.framework.flow.api.Operation;
import com.team4u.framework.flow.api.OperationContext;
import com.team4u.framework.flow.api.PersistentPolicy;
import com.team4u.framework.flow.api.Policy;
import com.team4u.framework.flow.api.PolicyContext;
import com.team4u.framework.flow.model.Completion;
import com.team4u.framework.flow.model.Failure;
import com.team4u.framework.flow.model.Outcome;
import com.team4u.framework.flow.model.Recovery;
import com.team4u.framework.flow.model.Resumed;
import org.junit.Assert;
import org.junit.Test;

public class GenericTypeResolverTest {

    static class DirectOp implements Operation<String, Integer> {
        @Override
        public Outcome<Integer> execute(OperationContext context, String input) {
            return Outcome.accepted(input.length());
        }
    }

    abstract static class BaseOp<T, R> implements Operation<T, R> {
    }

    static class SubOp extends BaseOp<Long, Double> {
        @Override
        public Outcome<Double> execute(OperationContext context, Long input) {
            return Outcome.accepted(input.doubleValue());
        }
    }

    abstract static class MiddleOp<X> extends BaseOp<X, String> {
    }

    static class LeafOp extends MiddleOp<Integer> {
        @Override
        public Outcome<String> execute(OperationContext context, Integer input) {
            return Outcome.accepted(String.valueOf(input));
        }
    }

    interface CustomOp<A, B> extends Operation<A, B> {
    }

    interface SpecializedOp<T> extends CustomOp<T, Boolean> {
    }

    static class MultiLevelImpl implements SpecializedOp<String> {
        @Override
        public Outcome<Boolean> execute(OperationContext context, String input) {
            return Outcome.accepted(!input.isEmpty());
        }
    }

    abstract static class BoundedOp<T extends Number> implements Operation<T, String> {
    }

    static class ResumingOp implements Operation<String, Resumed<String, Integer>> {
        @Override
        public Outcome<Resumed<String, Integer>> execute(OperationContext context, String input) {
            return Outcome.accepted(new Resumed<String, Integer>(input, 1));
        }
    }

    static class RecoveryOp implements Operation<String, Recovery<String>> {
        @Override
        public Outcome<Recovery<String>> execute(OperationContext context, String input) {
            return Outcome.accepted(new Recovery<String>(input, Failure.of("ERR", "error")));
        }
    }

    static class ArrayOp implements Operation<byte[], String[]> {
        @Override
        public Outcome<String[]> execute(OperationContext context, byte[] input) {
            return Outcome.accepted(new String[0]);
        }
    }

    abstract static class GenericBoundOp<T extends CharSequence, R> implements Operation<T, R> {
    }

    @SuppressWarnings("rawtypes")
    static class RawOp implements Operation {
        @Override
        public Outcome execute(OperationContext context, Object input) {
            return Outcome.accepted(input);
        }
    }

    static class SimplePolicy implements Policy<String> {
        @Override
        public Gate before(PolicyContext context, String key) {
            return Gate.proceed();
        }
    }

    abstract static class BasePolicy<K> implements Policy<K> {
    }

    static class SubPolicy extends BasePolicy<Long> {
        @Override
        public Gate before(PolicyContext context, Long key) {
            return Gate.proceed();
        }
    }

    static class SimplePersistentPolicy implements PersistentPolicy<String, Integer> {
        @Override
        public Integer initialState(String key) {
            return 0;
        }

        @Override
        public Before<Integer> before(PolicyContext context, String key, Integer state) {
            return PersistentPolicy.proceed(state + 1);
        }

        @Override
        public After<Integer> after(PolicyContext context, String key, Integer state, Completion completion) {
            return PersistentPolicy.returning(state);
        }
    }

    abstract static class BasePP<K, S> implements PersistentPolicy<K, S> {
    }

    static class SubPP extends BasePP<Long, Double> {
        @Override
        public Double initialState(Long key) {
            return 0.0;
        }

        @Override
        public Before<Double> before(PolicyContext context, Long key, Double state) {
            return PersistentPolicy.proceed(state + 1.0);
        }

        @Override
        public After<Double> after(PolicyContext context, Long key, Double state, Completion completion) {
            return PersistentPolicy.returning(state);
        }
    }

    @Test
    public void resolveDirectImplementation() {
        TypeRef[] types = GenericTypeResolver.resolveOperationTypes(DirectOp.class);
        Assert.assertEquals(TypeRef.of(String.class), types[0]);
        Assert.assertEquals(TypeRef.of(Integer.class), types[1]);
    }

    @Test
    public void resolveAbstractSuperclassInheritance() {
        TypeRef[] types = GenericTypeResolver.resolveOperationTypes(SubOp.class);
        Assert.assertEquals(TypeRef.of(Long.class), types[0]);
        Assert.assertEquals(TypeRef.of(Double.class), types[1]);
    }

    @Test
    public void resolvePropagatedTypeVariables() {
        TypeRef[] types = GenericTypeResolver.resolveOperationTypes(LeafOp.class);
        Assert.assertEquals(TypeRef.of(Integer.class), types[0]);
        Assert.assertEquals(TypeRef.of(String.class), types[1]);
    }

    @Test
    public void resolveMultiLevelInterfaceInheritance() {
        TypeRef[] types = GenericTypeResolver.resolveOperationTypes(MultiLevelImpl.class);
        Assert.assertEquals(TypeRef.of(String.class), types[0]);
        Assert.assertEquals(TypeRef.of(Boolean.class), types[1]);
    }

    @Test
    public void resolveInterfaceDirectly() {
        TypeRef[] types = GenericTypeResolver.resolveOperationTypes(SpecializedOp.class);
        Assert.assertEquals(TypeRef.ANY, types[0]);
        Assert.assertEquals(TypeRef.of(Boolean.class), types[1]);
    }

    @Test
    public void resolveBoundedTypeVariable() {
        TypeRef[] types = GenericTypeResolver.resolveOperationTypes(BoundedOp.class);
        Assert.assertEquals(TypeRef.of(Number.class), types[0]);
        Assert.assertEquals(TypeRef.of(String.class), types[1]);
    }

    @Test
    public void resolveResumingAndRecoveryTypes() {
        TypeRef[] resumeTypes = GenericTypeResolver.resolveOperationTypes(ResumingOp.class);
        Assert.assertEquals(TypeRef.of(String.class), resumeTypes[0]);
        Assert.assertEquals(TypeRef.resumed(TypeRef.of(String.class), TypeRef.of(Integer.class)), resumeTypes[1]);

        TypeRef[] recoverTypes = GenericTypeResolver.resolveOperationTypes(RecoveryOp.class);
        Assert.assertEquals(TypeRef.of(String.class), recoverTypes[0]);
        Assert.assertEquals(TypeRef.recovery(TypeRef.of(String.class)), recoverTypes[1]);
    }

    @Test
    public void resolveArrayAndGenericBoundTypes() {
        TypeRef[] arrayTypes = GenericTypeResolver.resolveOperationTypes(ArrayOp.class);
        Assert.assertEquals(TypeRef.of(byte[].class), arrayTypes[0]);
        Assert.assertEquals(TypeRef.of(String[].class), arrayTypes[1]);

        TypeRef[] boundTypes = GenericTypeResolver.resolveOperationTypes(GenericBoundOp.class);
        Assert.assertEquals(TypeRef.of(CharSequence.class), boundTypes[0]);
        Assert.assertEquals(TypeRef.ANY, boundTypes[1]);
    }

    @Test
    public void resolvePolicyKeyTypes() {
        TypeRef keyType1 = GenericTypeResolver.resolvePolicyKeyType(SimplePolicy.class);
        Assert.assertEquals(TypeRef.of(String.class), keyType1);

        TypeRef keyType2 = GenericTypeResolver.resolvePolicyKeyType(SubPolicy.class);
        Assert.assertEquals(TypeRef.of(Long.class), keyType2);

        TypeRef keyTypeNull = GenericTypeResolver.resolvePolicyKeyType(null);
        Assert.assertEquals(TypeRef.ANY, keyTypeNull);

        TypeRef keyTypeNonPolicy = GenericTypeResolver.resolvePolicyKeyType(String.class);
        Assert.assertEquals(TypeRef.ANY, keyTypeNonPolicy);
    }

    @Test
    public void resolvePersistentPolicyTypes() {
        TypeRef[] types1 = GenericTypeResolver.resolvePersistentPolicyTypes(SimplePersistentPolicy.class);
        Assert.assertEquals(TypeRef.of(String.class), types1[0]);
        Assert.assertEquals(TypeRef.of(Integer.class), types1[1]);

        TypeRef[] types2 = GenericTypeResolver.resolvePersistentPolicyTypes(SubPP.class);
        Assert.assertEquals(TypeRef.of(Long.class), types2[0]);
        Assert.assertEquals(TypeRef.of(Double.class), types2[1]);

        TypeRef[] typesNull = GenericTypeResolver.resolvePersistentPolicyTypes(null);
        Assert.assertEquals(TypeRef.ANY, typesNull[0]);
        Assert.assertEquals(TypeRef.ANY, typesNull[1]);

        TypeRef[] typesNonPolicy = GenericTypeResolver.resolvePersistentPolicyTypes(String.class);
        Assert.assertEquals(TypeRef.ANY, typesNonPolicy[0]);
        Assert.assertEquals(TypeRef.ANY, typesNonPolicy[1]);
    }

    @Test
    public void resolveFallbacks() {
        TypeRef[] rawTypes = GenericTypeResolver.resolveOperationTypes(RawOp.class);
        Assert.assertEquals(TypeRef.ANY, rawTypes[0]);
        Assert.assertEquals(TypeRef.ANY, rawTypes[1]);

        TypeRef[] nonOpTypes = GenericTypeResolver.resolveOperationTypes(String.class);
        Assert.assertEquals(TypeRef.ANY, nonOpTypes[0]);
        Assert.assertEquals(TypeRef.ANY, nonOpTypes[1]);

        TypeRef[] nullTypes = GenericTypeResolver.resolveOperationTypes(null);
        Assert.assertEquals(TypeRef.ANY, nullTypes[0]);
        Assert.assertEquals(TypeRef.ANY, nullTypes[1]);
    }
}
