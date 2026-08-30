package com.team4u.it;

import com.team4u.framework.base.util.TextTemplate;

import java.util.HashMap;
import java.util.Map;

public class MinimalConsumer {

    public static void main(String[] args) {
        Map<String, Object> values = new HashMap<>();
        values.put("name", "Team4u");
        String result = new TextTemplate("Hello, ${name}!").render(values);

        if (!"Hello, Team4u!".equals(result)) {
            throw new IllegalStateException("Unexpected TextTemplate result: " + result);
        }
        System.out.println(result);
    }
}
