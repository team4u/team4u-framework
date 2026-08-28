package com.team4u.framework.mask.config;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.mask.MaskRuleResolver;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MaskConfigQuickstartTest {

    private TestConfigContext configContext;

    @Before
    public void setUp() {
        MaskRuleResolver.Global.reset();
        configContext = TestConfigContext.create();
    }

    @After
    public void tearDown() {
        MaskBootstrap.global().stop();
        configContext.destroy();
        MaskRuleResolver.Global.reset();
    }

    @Test
    public void configRulesInstallUpdateAndUninstallGlobalResolver() {
        configContext.put("team4u.mask.rules",
                "{\"" + Payload.class.getName() + "\":{\"mobile\":\"MOBILE\"}}");
        MaskBootstrap.global().start(configContext.getConfigManager());

        Assert.assertSame(MaskRuleResolver.Global.get(), MaskRuleRepository.getInstance());
        Assert.assertEquals("MOBILE", MaskRuleResolver.Global.get().findRule(Payload.class.getName(), "mobile"));

        configContext.put("team4u.mask.rules",
                "{\"*\":{\"email\":\"EMAIL\"}}");
        Assert.assertEquals("EMAIL", MaskRuleResolver.Global.get().findRule(Payload.class.getName(), "email"));

        MaskBootstrap.global().stop();
        Assert.assertSame(MaskRuleResolver.NO_OP, MaskRuleResolver.Global.get());
    }

    @Test
    public void startIsRestartableAndInvalidHotUpdateKeepsActiveRules() {
        configContext.put("team4u.mask.rules",
                "{\"" + Payload.class.getName() + "\":{\"mobile\":\"MOBILE\"}}");
        MaskBootstrap.global().start(configContext.getConfigManager());
        MaskBootstrap.global().start(configContext.getConfigManager());

        Assert.assertEquals("MOBILE", MaskRuleResolver.Global.get().findRule(Payload.class.getName(), "mobile"));

        configContext.put("team4u.mask.rules", "{invalid-json");

        Assert.assertSame(MaskRuleRepository.getInstance(), MaskRuleResolver.Global.get());
        Assert.assertEquals("MOBILE", MaskRuleResolver.Global.get().findRule(Payload.class.getName(), "mobile"));
    }

    @Test
    public void invalidInitialRulesFailStartAndLeaveNoResolverInstalled() {
        configContext.put("team4u.mask.rules", "{invalid-json");
        try {
            MaskBootstrap.global().start(configContext.getConfigManager());
            Assert.fail("Invalid initial mask rules must fail start");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("Invalid mask rule config", e.getMessage());
        }

        Assert.assertSame(MaskRuleResolver.NO_OP, MaskRuleResolver.Global.get());
        Assert.assertNull(MaskRuleResolver.Global.get().findRule(Payload.class.getName(), "mobile"));
    }

    @Test
    public void repositoryMatchesPreciseClassBeforeGlobalWildcard() {
        Map<String, Map<String, String>> rules = new HashMap<>();
        rules.put(Payload.class.getName(), Collections.singletonMap("mobile", "MOBILE"));
        rules.put("*", Collections.singletonMap("email", "EMAIL"));
        MaskRuleRepository.getInstance().setRuleCache(rules);

        Assert.assertEquals("MOBILE", MaskRuleRepository.getInstance().findRule(Payload.class.getName(), "mobile"));
        Assert.assertEquals("EMAIL", MaskRuleRepository.getInstance().findRule("com.example.Other", "email"));
        Assert.assertEquals("EMAIL", MaskRuleRepository.getInstance().findRule(Payload.class.getName(), "email"));
    }

    private static final class Payload {
    }
}
