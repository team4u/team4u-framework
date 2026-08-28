package com.team4u.framework.serializer.json;

import com.team4u.framework.serializer.json.jackson.JacksonSerializerPolicy;
import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JacksonServiceProviderContractTest {

    @Test
    public void serviceLoaderResourceNamesJacksonPolicy() throws IOException {
        String resource = "META-INF/services/" + JsonSerializerPolicy.class.getName();
        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        Assert.assertNotNull("Missing ServiceLoader resource: " + resource, stream);

        List<String> implementations = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String candidate = line.trim();
                if (!candidate.isEmpty() && !candidate.startsWith("#")) {
                    implementations.add(candidate);
                }
            }
        }

        Assert.assertEquals(
                Collections.singletonList(JacksonSerializerPolicy.class.getName()),
                implementations);
    }

    @Test
    public void jsonUtilSelectsJacksonPolicy() {
        Assert.assertTrue(JsonUtil.getPolicy() instanceof JacksonSerializerPolicy);
    }

    @Test
    public void pojoRoundTripUsesJacksonPolicy() {
        User user = new User();
        user.setName("jay");
        user.setAge(18);

        String json = JsonUtil.toJsonStr(user);
        User decoded = JsonUtil.toBean(json, User.class);

        Assert.assertEquals("jay", decoded.getName());
        Assert.assertEquals(Integer.valueOf(18), decoded.getAge());
    }

    public static class User {
        private String name;
        private Integer age;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }
}
