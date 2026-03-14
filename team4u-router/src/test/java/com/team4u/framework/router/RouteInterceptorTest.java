package com.team4u.framework.router;

import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.router.api.interceptor.RouteInterceptor;
import com.team4u.framework.router.api.interceptor.RouteInvocation;
import com.team4u.framework.router.api.model.RouteResult;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 路由拦截器单元测试
 */
public class RouteInterceptorTest {

    @Test
    public void testInterceptorChain() {
        List<String> logs = new ArrayList<>();

        RouteInterceptor interceptor1 = new RouteInterceptor() {
            @Override
            public <T> RouteResult<T> intercept(RouteInvocation<T> invocation) {
                logs.add("pre1");
                RouteResult<T> result = invocation.proceed();
                logs.add("post1");
                return result;
            }

            @Override
            public int priority() {
                return 1;
            }
        };

        RouteInterceptor interceptor2 = new RouteInterceptor() {
            @Override
            public <T> RouteResult<T> intercept(RouteInvocation<T> invocation) {
                logs.add("pre2");
                RouteResult<T> result = invocation.proceed();
                logs.add("post2");
                return result;
            }

            @Override
            public int priority() {
                return 2;
            }
        };

        RoutingManager routingManager = RoutingManager.builder()
                .addInterceptor(interceptor2) // 故意乱序添加
                .addInterceptor(interceptor1)
                .build();

        String config = "{\"type\":\"map\", \"rules\":[{\"condition\":\"k\", \"value\":\"v\"}]}";
        RouteResult<String> result = routingManager.routeByConfig(config, "k");

        Assert.assertEquals("v", result.getValue());
        // 验证执行顺序：1在前，2在后
        Assert.assertEquals("pre1", logs.get(0));
        Assert.assertEquals("pre2", logs.get(1));
        Assert.assertEquals("post2", logs.get(2));
        Assert.assertEquals("post1", logs.get(3));
    }

    @Test
    public void testModifyRequest() {
        RouteInterceptor modifier = new RouteInterceptor() {
            @Override
            public <T> RouteResult<T> intercept(RouteInvocation<T> invocation) {
                String req = (String) invocation.getRequest();
                invocation.setRequest(req + "-modified");
                return invocation.proceed();
            }
        };

        RoutingManager routingManager = RoutingManager.builder()
                .addInterceptor(modifier)
                .build();

        String config = "{\"type\":\"map\", \"rules\":[{\"condition\":\"k-modified\", \"value\":\"v\"}]}";
        RouteResult<String> result = routingManager.routeByConfig(config, "k");

        Assert.assertTrue(result.isMatch());
        Assert.assertEquals("v", result.getValue());
    }

    @Test
    public void testShortCircuit() {
        RouteInterceptor shortCircuit = new RouteInterceptor() {
            @Override
            public <T> RouteResult<T> intercept(RouteInvocation<T> invocation) {
                // 不调用 invocation.proceed()，直接返回结果
                return RouteResult.shortCircuited((T) "short-circuit", "hit");
            }
        };

        RoutingManager routingManager = RoutingManager.builder()
                .addInterceptor(shortCircuit)
                .build();

        String config = "{\"type\":\"map\", \"rules\":[{\"condition\":\"k\", \"value\":\"v\"}]}";
        RouteResult<String> result = routingManager.routeByConfig(config, "k");

        Assert.assertEquals("short-circuit", result.getValue());
        Assert.assertTrue(result.isShortCircuited());
    }

    @Test
    public void testExceptionHandling() {
        RouteInterceptor errorHandler = new RouteInterceptor() {
            @Override
            public <T> RouteResult<T> intercept(RouteInvocation<T> invocation) {
                try {
                    return invocation.proceed();
                } catch (Exception e) {
                    return RouteResult.unmatch();
                }
            }
        };

        RouteInterceptor errorProducer = new RouteInterceptor() {
            @Override
            public <T> RouteResult<T> intercept(RouteInvocation<T> invocation) {
                throw new RuntimeException("boom");
            }

            @Override
            public int priority() {
                return 10;
            }
        };

        RoutingManager routingManager = RoutingManager.builder()
                .addInterceptor(errorHandler)
                .addInterceptor(errorProducer)
                .build();

        RouteResult<String> result = routingManager.routeByConfig("{\"type\":\"map\"}", "k");
        Assert.assertFalse(result.isMatch());
    }

    @Test
    public void testTargetTypeMetadataCompatibility() {
        List<String> logs = new ArrayList<>();

        RouteInterceptor inspector = new RouteInterceptor() {
            @Override
            public <T> RouteResult<T> intercept(RouteInvocation<T> invocation) {
                Type genericType = invocation.getTargetGenericType();
                logs.add("class=" + invocation.getTargetType());
                logs.add("generic=" + genericType);
                return invocation.proceed();
            }
        };

        RoutingManager routingManager = RoutingManager.builder()
                .addInterceptor(inspector)
                .build();

        String classConfig = "{\"type\":\"map\", \"rules\":[{\"condition\":\"bean\", \"value\":{\"value\":\"ok\"}}]}";
        RouteResult<TestBean> classResult = routingManager.routeByConfig(classConfig, "bean", TestBean.class);
        Assert.assertTrue(classResult.isMatch());
        Assert.assertEquals("class=class " + TestBean.class.getName(), logs.get(0));
        Assert.assertEquals("generic=class " + TestBean.class.getName(), logs.get(1));

        String listConfig = "{\"type\":\"map\", \"rules\":[{\"condition\":\"list\", \"value\":[{\"value\":\"a\"}]}]}";
        RouteResult<List<TestBean>> listResult = routingManager.routeByConfig(
                listConfig,
                "list",
                new TypeReference<List<TestBean>>() {
                });
        Assert.assertTrue(listResult.isMatch());
        Assert.assertEquals("a", listResult.getValue().get(0).getValue());
        Assert.assertEquals("class=null", logs.get(2));
        Assert.assertTrue(logs.get(3).contains("java.util.List"));
    }

    public static class TestBean {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
