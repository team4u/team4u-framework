package com.team4u.framework.mask;

import com.team4u.framework.policy.api.KeyedPolicy;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
public class MaskQuickstartTest {

    @After
    public void tearDown() {
        MaskRuleResolver.Global.reset();
    }

    @Test
    public void maskBuiltInPoliciesWithStaticFacade() {
        Assert.assertEquals("138*****000", FastMasker.mask("13800138000", MaskType.MOBILE));
        Assert.assertEquals("j****@gmail.com", FastMasker.mask("jay.wuy@gmail.com", "EMAIL"));
        Assert.assertEquals("12345", FastMasker.mask("12345", MaskType.NONE));
        Assert.assertNull(FastMasker.mask("12345", MaskType.NULL));
    }

    @Test
    public void registerCustomPolicyForApplicationSpecificKey() {
        FastMasker.register(new PassportMaskPolicy());

        Assert.assertEquals("E1*****78", FastMasker.mask("E12345678", "PASSPORT"));
    }

    @Test
    public void globalRuleResolverDefaultsToNoOpAndResetsCleanly() {
        Assert.assertSame(MaskRuleResolver.NO_OP, MaskRuleResolver.Global.get());

        MaskRuleResolver resolver = (className, fieldName) -> "MOBILE";
        MaskRuleResolver.Global.install(resolver);

        Assert.assertSame(resolver, MaskRuleResolver.Global.get());
        Assert.assertEquals("MOBILE", MaskRuleResolver.Global.get().findRule("com.example.User", "mobile"));

        MaskRuleResolver.Global.reset();
        Assert.assertSame(MaskRuleResolver.NO_OP, MaskRuleResolver.Global.get());
        Assert.assertNull(MaskRuleResolver.Global.get().findRule("com.example.User", "mobile"));
    }

    @Test
    public void globalRuleResolverUninstallUsesOwnershipComparison() {
        MaskRuleResolver owner = (className, fieldName) -> "MOBILE";
        MaskRuleResolver newer = (className, fieldName) -> "EMAIL";

        MaskRuleResolver.Global.install(owner);
        Assert.assertFalse(MaskRuleResolver.Global.uninstall(newer));
        Assert.assertSame(owner, MaskRuleResolver.Global.get());

        Assert.assertTrue(MaskRuleResolver.Global.uninstall(owner));
        Assert.assertSame(MaskRuleResolver.NO_OP, MaskRuleResolver.Global.get());
        Assert.assertFalse(MaskRuleResolver.Global.uninstall(owner));
        Assert.assertNull(MaskRuleResolver.Global.get().findRule("com.example.User", "mobile"));
    }


    @Test
    public void maskPolicyPreservesKeyedPolicyCompatibility() {
        Assert.assertTrue(KeyedPolicy.class.isAssignableFrom(PassportMaskPolicy.class));
    }
    @Test
    public void annotationDeclaresMaskPolicyWithoutSerializerDependency() {
        Mask annotation = AnnotatedValue.class.getDeclaredFields()[0].getAnnotation(Mask.class);

        Assert.assertNotNull(annotation);
        Assert.assertSame(MaskType.MOBILE, annotation.value());
    }

    @Test
    public void fastMaskerKeepsPublicExtensionSurface() throws Exception {
        Assert.assertFalse(Modifier.isFinal(FastMasker.class.getModifiers()));

        Constructor<FastMasker> constructor = FastMasker.class.getDeclaredConstructor();
        Assert.assertTrue(Modifier.isPublic(constructor.getModifiers()));
        Assert.assertNotNull(constructor.newInstance());
    }

    private static final class PassportMaskPolicy implements MaskPolicy {
        @Override
        public String key() {
            return "PASSPORT";
        }

        @Override
        public String mask(String value) {
            return MaskUtils.mask(value, 2, 2);
        }
    }

    private static final class AnnotatedValue {
        @Mask(MaskType.MOBILE)
        private final String mobile = "13800138000";
    }
}
