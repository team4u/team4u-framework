package com.team4u.it;

import com.team4u.framework.serializer.json.JsonUtil;

public class NoProviderMain {

    public static void main(String[] args) {
        try {
            String json = JsonUtil.toJsonStr(new Object());
            throw new IllegalStateException("JSON API unexpectedly succeeded without a provider: " + json);
        } catch (IllegalStateException expected) {
            String message = String.valueOf(expected.getMessage());
            if (!message.contains("JsonSerializerPolicy")) {
                throw new IllegalStateException("No-provider error is not clear: " + message, expected);
            }
            System.out.println(message);
        }
    }
}
