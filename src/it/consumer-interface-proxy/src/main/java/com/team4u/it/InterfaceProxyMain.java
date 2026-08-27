package com.team4u.it;

import com.team4u.framework.proxy.ProxyBuilder;

public class InterfaceProxyMain {

    public static void main(String[] args) {
        Greeting delegate = new Greeting() {
            @Override
            public String greet(String message) {
                return "Hello, " + message + "!";
            }
        };
        Greeting proxy = ProxyBuilder.forClass(Greeting.class)
                .withDelegate(delegate)
                .build();

        String result = proxy.greet("Team4u");
        if (!"Hello, Team4u!".equals(result)) {
            throw new IllegalStateException("JDK interface proxy failed: " + result);
        }
        if (!proxy.getClass().getName().startsWith("com.sun.proxy.") && !java.lang.reflect.Proxy.isProxyClass(proxy.getClass())) {
            throw new IllegalStateException("Expected a JDK dynamic proxy: " + proxy.getClass().getName());
        }
        System.out.println(result);
    }

    public interface Greeting {
        String greet(String message);
    }
}
