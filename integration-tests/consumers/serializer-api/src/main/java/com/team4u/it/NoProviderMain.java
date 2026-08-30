package com.team4u.it;

import com.team4u.framework.serializer.json.JsonUtil;

public class NoProviderMain {

    public static void main(String[] args) {
        try {
            String json = JsonUtil.toJsonStr(new Object());
            throw new IllegalStateException("JSON API unexpectedly succeeded without a provider: " + json);
        } catch (IllegalStateException expected) {
            String message = String.valueOf(expected.getMessage());
            boolean namesJacksonProvider = message.contains("com.team4u:team4u-serializer-jackson");
            boolean explainsCustomProvider = message.contains("custom") && message.contains("JsonSerializerPolicy");
            if (!namesJacksonProvider && !explainsCustomProvider) {
                throw new IllegalStateException(
                        "No-provider error does not explain the Jackson provider or a custom JsonSerializerPolicy: " + message,
                        expected);
            }
            System.out.println(message);
        }
    }
}
