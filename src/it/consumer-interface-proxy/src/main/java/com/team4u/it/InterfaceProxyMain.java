package com.team4u.it;

import com.team4u.framework.proxy.ProxyBuilder;
import com.team4u.framework.proxy.core.ProxyException;

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
        if (!java.lang.reflect.Proxy.isProxyClass(proxy.getClass())) {
            throw new IllegalStateException("Expected a JDK dynamic proxy: " + proxy.getClass().getName());
        }

        try {
            ProxyBuilder.forClass(ConcreteService.class)
                    .withDelegate(new ConcreteService())
                    .build();
        } catch (ProxyException e) {
            String expected = "Class proxy requires the optional dependency net.bytebuddy:byte-buddy.\n"
                    + "JDK interface proxies run without ByteBuddy.";
            if (!expected.equals(e.getMessage())) {
                throw new IllegalStateException("Unexpected class proxy failure:\n" + e.getMessage(), e);
            }
        }
        System.out.println(result);
    }

    public static class ConcreteService {
        public String serve() {
            return "service";
        }
    }

    public interface Greeting {
        String greet(String message);
    }
}
