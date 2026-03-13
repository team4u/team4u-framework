package com.team4u.framework.serializer.json;

import com.team4u.framework.serializer.json.jackson.JacksonSerializerPolicy;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class JsonUtilTest {

    @Test
    public void testPolicyLoaded() {
        JsonSerializerPolicy policy = JsonUtil.getPolicy();
        Assert.assertTrue(policy instanceof JacksonSerializerPolicy);
    }

    @Test
    public void testToJsonAndBean() {
        User user = new User();
        user.setName("jay");
        user.setAge(18);

        String json = JsonUtil.toJsonStr(user);
        Assert.assertNotNull(json);

        User decoded = JsonUtil.toBean(json, User.class);
        Assert.assertEquals(user.getName(), decoded.getName());
        Assert.assertEquals(user.getAge(), decoded.getAge());
    }

    @Test
    public void testTypeReference() {
        String json = "[{\"name\":\"jay\"}]";
        List<User> users = JsonUtil.toBean(json, new TypeReference<List<User>>() {
        });

        Assert.assertEquals(1, users.size());
        Assert.assertEquals("jay", users.get(0).getName());
    }

    @Test
    public void testParseObj() {
        String json = "{\"name\":\"jay\"}";
        Object obj = JsonUtil.parseObj(json);
        Assert.assertNotNull(obj);
        // 在 Jackson 下，它应该是一个 JsonNode 的子类，但我们按 Object 处理
        Assert.assertTrue(obj.toString().contains("jay"));
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
