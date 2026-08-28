package com.team4u.framework.mask.config;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.mask.MaskRuleResolver;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MaskConfigSecurityLifecycleTest {

    private TestConfigContext configContext;

    @Before
    public void setUp() {
        MaskRuleRepository.getInstance().reset();
        MaskRuleResolver.Global.reset();
        configContext = TestConfigContext.create();
    }

    @After
    public void tearDown() {
        MaskRuleRepository.getInstance().reset();
        configContext.destroy();
        MaskRuleResolver.Global.reset();
    }

    @Test
    public void explicitNullInitialRuleFailsClosedWithoutInstallingResolver() {
        configContext.put("team4u.mask.rules",
                "{\"" + Payload.class.getName() + "\":{\"email\":null}}");

        try {
            MaskBootstrap.global().start(configContext.getConfigManager());
            Assert.fail("Explicit null mask rule must fail closed");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().startsWith("Mask rule must not be null:"));
        }

        Assert.assertSame(MaskRuleResolver.NO_OP, MaskRuleResolver.Global.get());
    }

    @Test
    public void explicitNullHotUpdatePreservesPreviousValidRules() {
        configContext.put("team4u.mask.rules",
                "{\"" + Payload.class.getName() + "\":{\"email\":\"EMAIL\"}}");
        MaskBootstrap.global().start(configContext.getConfigManager());

        configContext.put("team4u.mask.rules",
                "{\"" + Payload.class.getName() + "\":{\"email\":null}}");

        Assert.assertSame(MaskRuleRepository.getInstance(), MaskRuleResolver.Global.get());
        Assert.assertEquals("EMAIL",
                MaskRuleResolver.Global.get().findRule(Payload.class.getName(), "email"));
    }

    @Test
    public void exactFieldMissReturnsNullUnlessWildcardDefinesField() {
        configContext.put("team4u.mask.rules", "{\"*\":{\"email\":\"EMAIL\"}}");
        MaskBootstrap.global().start(configContext.getConfigManager());

        Assert.assertNull(MaskRuleRepository.getInstance().findRule(Payload.class.getName(), "mobile"));
        Assert.assertEquals("EMAIL",
                MaskRuleRepository.getInstance().findRule(Payload.class.getName(), "email"));
    }

    @Test
    public void manualExplicitNullRuleIsRejectedBeforePublication() {
        Map<String, String> classRules = new HashMap<>();
        classRules.put("email", null);
        classRules.put("mobile", "MOBILE");

        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put(Payload.class.getName(), classRules);
        rules.put("*", Collections.singletonMap("email", "NAME"));

        try {
            MaskRuleRepository.getInstance().setRuleCache(rules);
            Assert.fail("Explicit null manual mask rule must fail publication");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("Mask rule must not be null: "
                            + Payload.class.getName() + ".email",
                    e.getMessage());
        }
    }

    @Test
    public void manualNullClassRulesAreRejectedBeforePublication() {
        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put(Payload.class.getName(), null);
        rules.put("*", Collections.singletonMap("email", "EMAIL"));

        try {
            MaskRuleRepository.getInstance().setRuleCache(rules);
            Assert.fail("Null manual class rules must fail publication");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("Mask class rules must not be null: "
                    + Payload.class.getName(), e.getMessage());
        }
    }

    @Test
    public void manualRulesUseDefensiveDeepSnapshotAfterPublication() {
        Map<String, String> classRules = new HashMap<>();
        classRules.put("email", "EMAIL");

        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put(Payload.class.getName(), classRules);

        MaskRuleRepository.getInstance().setRuleCache(rules);

        classRules.put("email", null);
        rules.put(Payload.class.getName(), null);

        Assert.assertEquals("EMAIL",
                MaskRuleRepository.getInstance().findRule(Payload.class.getName(), "email"));
        Assert.assertNull(MaskRuleRepository.getInstance().findRule(Payload.class.getName(), "mobile"));
    }

    @Test
    public void malformedStateWithNullClassRulesFailsClosedDuringLookup() throws Exception {
        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put(Payload.class.getName(), null);
        rules.put("*", null);
        publishMalformedManualRules(rules);

        try {
            MaskRuleRepository.getInstance().findRule(Payload.class.getName(), "email");
            Assert.fail("Null exact class rules must fail lookup");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("Mask class rules must not be null: "
                    + Payload.class.getName(), e.getMessage());
        }

        try {
            MaskRuleRepository.getInstance().findRule("Other", "email");
            Assert.fail("Null wildcard class rules must fail lookup");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("Mask class rules must not be null: *", e.getMessage());
        }
    }

    private static void publishMalformedManualRules(Map<String, Map<String, String>> rules)
            throws Exception {
        Field field = MaskRuleRepository.class.getDeclaredField("manualRuleCache");
        field.setAccessible(true);
        field.set(MaskRuleRepository.getInstance(), rules);
    }

    @Test
    public void resetAndStopDoNotClearANewerResolverOwner() {
        configContext.put("team4u.mask.rules",
                "{\"" + Payload.class.getName() + "\":{\"mobile\":\"MOBILE\"}}");
        MaskBootstrap.global().start(configContext.getConfigManager());

        MaskRuleResolver newerResolver = (className, fieldName) -> "EMAIL";
        MaskRuleResolver.Global.install(newerResolver);

        MaskRuleRepository.getInstance().reset();
        MaskBootstrap.global().stop();

        Assert.assertSame(newerResolver, MaskRuleResolver.Global.get());
        Assert.assertEquals("EMAIL", MaskRuleResolver.Global.get().findRule(Payload.class.getName(), "mobile"));
    }

    private static final class Payload {
    }
}
