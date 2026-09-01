package com.team4u.framework.flow.definition.binding;

import com.team4u.framework.flow.definition.model.*;
import org.junit.Assert;
import org.junit.Test;

public class SpecBinderRegistryTest {

    @Test
    public void testGlobalRegistryContainsAllSpecBinders() {
        SpecBinderRegistry registry = SpecBinderRegistry.global();
        Assert.assertTrue(registry.isFrozen());

        Assert.assertTrue(registry.get(StepSpec.class).isPresent());
        Assert.assertTrue(registry.get(SequenceSpec.class).isPresent());
        Assert.assertTrue(registry.get(RouteSpec.class).isPresent());
        Assert.assertTrue(registry.get(FirstApplicableSpec.class).isPresent());
        Assert.assertTrue(registry.get(RecoverSpec.class).isPresent());
        Assert.assertTrue(registry.get(ParallelSpec.class).isPresent());
        Assert.assertTrue(registry.get(AwaitSpec.class).isPresent());
        Assert.assertTrue(registry.get(CompleteSpec.class).isPresent());
        Assert.assertTrue(registry.get(ControlSpec.class).isPresent());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testFrozenCannotRegister() {
        SpecBinderRegistry registry = SpecBinderRegistry.global();
        registry.register(new SpecBinders.StepSpecBinder());
    }
}
