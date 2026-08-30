package com.team4u.framework.serializer.json;

import org.junit.Assert;
import org.junit.Test;

public class JsonUtilNoProviderContractTest {

    private static final String EXPECTED_MESSAGE =
            "No JsonSerializerPolicy is available. Add com.team4u:team4u-serializer-jackson, "
                    + "or register/provide a custom JsonSerializerPolicy via ServiceLoader.";

    @Test
    public void noProviderFailsFastWithInstallGuidance() {
        try {
            JsonUtil.toJsonStr(new Object());
            Assert.fail("Expected IllegalStateException when no serializer provider is installed");
        } catch (IllegalStateException e) {
            Assert.assertEquals(EXPECTED_MESSAGE, e.getMessage());
        }
    }
}
