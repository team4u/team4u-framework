package com.team4u.framework.serializer.json;

import com.team4u.framework.base.util.TypeReference;
import org.junit.Assert;
import org.junit.Test;

public class SerializerQuickstartTest {

    @Test
    public void nullInputsShortCircuitWithoutAProvider() {
        Assert.assertNull(JsonUtil.toJsonStr(null));
        Assert.assertNull(JsonUtil.toBean(null, String.class));
        Assert.assertNull(JsonUtil.toBean("", String.class));
        Assert.assertNull(JsonUtil.toBean(null, new TypeReference<String>() {
        }));
        Assert.assertNull(JsonUtil.parseObj(null));
        Assert.assertNull(JsonUtil.parseObj(""));
        Assert.assertNull(JsonUtil.toList(null, String.class));
        Assert.assertNull(JsonUtil.toList("", String.class));
    }

    @Test
    public void noProviderFailureExplainsHowToInstallOne() {
        try {
            JsonUtil.toJsonStr(new Object());
            Assert.fail("Expected IllegalStateException when no serializer provider is installed");
        } catch (IllegalStateException e) {
            Assert.assertEquals(
                    "No JsonSerializerPolicy is available. Add com.team4u:team4u-serializer-jackson, "
                            + "or register/provide a custom JsonSerializerPolicy via ServiceLoader.",
                    e.getMessage());
        }
    }
}
