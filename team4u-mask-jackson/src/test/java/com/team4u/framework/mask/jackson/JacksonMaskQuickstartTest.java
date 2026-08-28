package com.team4u.framework.mask.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team4u.framework.mask.Mask;
import com.team4u.framework.mask.MaskRuleResolver;
import com.team4u.framework.mask.MaskType;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class JacksonMaskQuickstartTest {

    private final Map<String, Map<String, String>> rules = new LinkedHashMap<>();
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        MaskRuleResolver.Global.install((className, fieldName) -> {
            Map<String, String> classRules = rules.get(className);
            return classRules != null ? classRules.get(fieldName) : null;
        });
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JacksonMaskModule());
    }

    @After
    public void tearDown() {
        MaskRuleResolver.Global.reset();
    }

    @Test
    public void annotationAndDynamicRulesMaskSerializedValues() throws Exception {
        rules.put(Payload.class.getName(), Collections.singletonMap("email", "EMAIL"));

        Assert.assertEquals(
                "{\"mobile\":\"138*****000\",\"email\":\"j****@gmail.com\"}",
                objectMapper.writeValueAsString(new Payload()));
    }

    private static final class Payload {
        @Mask(MaskType.MOBILE)
        public final String mobile = "13800138000";

        public final String email = "jay.wuy@gmail.com";
    }
}
