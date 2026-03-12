package com.team4u.framework.retry.proxy;

import cn.hutool.json.JSONUtil;
import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.retry.proxy.invocation.InvocationArgSnapshot;
import com.team4u.framework.retry.proxy.invocation.InvocationRecoveryData;
import com.team4u.framework.retry.managed.recovery.RecoveryContext;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class InvocationReplayTest {

    private static InvocationArgSnapshot arg(Class<?> type, String serializedValue, boolean ignored) {
        return InvocationArgSnapshot.builder()
                .typeName(type.getName())
                .serializedValue(serializedValue)
                .ignored(ignored)
                .build();
    }

    @Test
    public void testRecoverResolvesPrimitiveParameters() throws Exception {
        PrimitiveReplayService service = new PrimitiveReplayService();
        BeanManager.getInstance().registerBean(PrimitiveReplayService.class.getName(), service);

        InvocationReplay replay = new InvocationReplay();
        replay.recover(JSONUtil.toJsonStr(InvocationRecoveryData.builder()
                        .targetTypeName(PrimitiveReplayService.class.getName())
                        .methodName("replay")
                        .args(Arrays.asList(
                                arg(int.class, "3", false),
                                arg(long.class, "4", false),
                                arg(boolean.class, "true", false),
                                arg(String.class, "\"done\"", false)))
                        .build()),
                RecoveryContext.builder().taskId("task-1").attempt(1).build());

        Assert.assertEquals(3, service.count);
        Assert.assertEquals(4L, service.total);
        Assert.assertTrue(service.enabled);
        Assert.assertEquals("done", service.label);
    }

    @Test
    public void testRecoverSupportsPrimitiveAndWrapperMix() throws Exception {
        MixedReplayService service = new MixedReplayService();
        BeanManager.getInstance().registerBean(MixedReplayService.class.getName(), service);

        InvocationReplay replay = new InvocationReplay();
        replay.recover(JSONUtil.toJsonStr(InvocationRecoveryData.builder()
                        .targetTypeName(MixedReplayService.class.getName())
                        .methodName("replay")
                        .args(Arrays.asList(
                                arg(boolean.class, "false", false),
                                arg(Integer.class, "7", false)))
                        .build()),
                RecoveryContext.builder().taskId("task-2").attempt(1).build());

        Assert.assertFalse(service.enabled);
        Assert.assertEquals(Integer.valueOf(7), service.retries);
    }

    @Test
    public void testRecoverSupportsEnumCharAndGenericCollection() throws Exception {
        GenericReplayService service = new GenericReplayService();
        BeanManager.getInstance().registerBean(GenericReplayService.class.getName(), service);

        InvocationReplay replay = new InvocationReplay();
        replay.recover(JSONUtil.toJsonStr(InvocationRecoveryData.builder()
                        .targetTypeName(GenericReplayService.class.getName())
                        .methodName("replay")
                        .args(Arrays.asList(
                                arg(Level.class, "\"HIGH\"", false),
                                arg(char.class, "\"A\"", false),
                                arg(List.class, "[{\"value\":\"x\"},{\"value\":\"y\"}]", false)))
                        .build()),
                RecoveryContext.builder().taskId("task-generic").attempt(1).build());

        Assert.assertEquals(Level.HIGH, service.level);
        Assert.assertEquals('A', service.grade);
        Assert.assertEquals(2, service.inputs.size());
        Assert.assertEquals("x", service.inputs.get(0).value);
        Assert.assertEquals("y", service.inputs.get(1).value);
    }

    @Test
    public void testRecoverPreservesIgnoredAndNullParameters() throws Exception {
        IgnoredReplayService service = new IgnoredReplayService();
        BeanManager.getInstance().registerBean(IgnoredReplayService.class.getName(), service);

        InvocationReplay replay = new InvocationReplay();
        replay.recover(JSONUtil.toJsonStr(InvocationRecoveryData.builder()
                        .targetTypeName(IgnoredReplayService.class.getName())
                        .methodName("replay")
                        .args(Arrays.asList(
                                arg(String.class, "\"order-1\"", false),
                                arg(Input.class, null, true),
                                arg(Integer.class, null, false)))
                        .build()),
                RecoveryContext.builder().taskId("task-3").attempt(1).build());

        Assert.assertEquals("order-1", service.orderId);
        Assert.assertNull(service.input);
        Assert.assertNull(service.attempts);
    }

    @Test
    public void testRecoverPrefersBeanNameWhenProvided() throws Exception {
        NamedReplayService primary = new NamedReplayService("primary");
        NamedReplayService secondary = new NamedReplayService("secondary");
        BeanManager.getInstance().registerBean("primaryReplayService", primary);
        BeanManager.getInstance().registerBean("secondaryReplayService", secondary);

        InvocationReplay replay = new InvocationReplay();
        replay.recover(JSONUtil.toJsonStr(InvocationRecoveryData.builder()
                        .targetTypeName(ReplayContract.class.getName())
                        .targetBeanName("secondaryReplayService")
                        .methodName("replay")
                        .args(Collections.singletonList(arg(String.class, "\"order-9\"", false)))
                        .build()),
                RecoveryContext.builder().taskId("task-bean-name").attempt(1).build());

        Assert.assertNull(primary.lastOrderId);
        Assert.assertEquals("order-9", secondary.lastOrderId);
    }

    @Test
    public void testRecoverRejectsAmbiguousBeansWithoutBeanName() {
        BeanManager.getInstance().registerBean("ambiguousReplayServiceA", new NamedReplayService("A"));
        BeanManager.getInstance().registerBean("ambiguousReplayServiceB", new NamedReplayService("B"));

        InvocationReplay replay = new InvocationReplay();
        try {
            replay.recover(JSONUtil.toJsonStr(InvocationRecoveryData.builder()
                            .targetTypeName(ReplayContract.class.getName())
                            .methodName("replay")
                            .args(Collections.singletonList(arg(String.class, "\"x\"", false)))
                            .build()),
                    RecoveryContext.builder().taskId("task-ambiguous").attempt(1).build());
            Assert.fail("expected IllegalStateException");
        } catch (Exception ex) {
            Assert.assertTrue(ex.getMessage().contains("Multiple managed beans found"));
        }
    }

    @Test
    public void testRecoverRejectsMissingSnapshotPayload() {
        InvocationReplay replay = new InvocationReplay();
        try {
            replay.recover(JSONUtil.toJsonStr(InvocationRecoveryData.builder()
                            .targetTypeName(PrimitiveReplayService.class.getName())
                            .methodName("replay")
                            .build()),
                    RecoveryContext.builder().taskId("task-4").attempt(1).build());
            Assert.fail("expected IllegalArgumentException");
        } catch (Exception ex) {
            Assert.assertTrue(ex.getMessage().contains("args is required"));
        }
    }

    @Test
    public void testRecoverFailsWhenBeanMissing() {
        InvocationReplay replay = new InvocationReplay();
        try {
            replay.recover(JSONUtil.toJsonStr(InvocationRecoveryData.builder()
                            .targetTypeName(MissingReplayService.class.getName())
                            .methodName("replay")
                            .args(Collections.singletonList(arg(String.class, "\"x\"", false)))
                            .build()),
                    RecoveryContext.builder().taskId("task-5").attempt(1).build());
            Assert.fail("expected IllegalStateException");
        } catch (Exception ex) {
            Assert.assertTrue(ex.getMessage().contains("No managed bean found"));
        }
    }

    @Test
    public void testRecoverRejectsIgnoredPrimitiveParameter() {
        PrimitiveIgnoredReplayService service = new PrimitiveIgnoredReplayService();
        BeanManager.getInstance().registerBean(PrimitiveIgnoredReplayService.class.getName(), service);

        InvocationReplay replay = new InvocationReplay();
        try {
            replay.recover(JSONUtil.toJsonStr(InvocationRecoveryData.builder()
                            .targetTypeName(PrimitiveIgnoredReplayService.class.getName())
                            .methodName("replay")
                            .args(Collections.singletonList(arg(int.class, null, true)))
                            .build()),
                    RecoveryContext.builder().taskId("task-6").attempt(1).build());
            Assert.fail("expected IllegalArgumentException");
        } catch (Exception ex) {
            Assert.assertTrue(ex.getMessage().contains("Ignored primitive parameter"));
        }
    }

    public enum Level {
        HIGH
    }

    public interface ReplayContract {
        void replay(String orderId);
    }

    public static class PrimitiveReplayService {
        private int count;
        private long total;
        private boolean enabled;
        private String label;

        public void replay(int count, long total, boolean enabled, String label) {
            this.count = count;
            this.total = total;
            this.enabled = enabled;
            this.label = label;
        }
    }

    public static class MixedReplayService {
        private boolean enabled;
        private Integer retries;

        public void replay(boolean enabled, Integer retries) {
            this.enabled = enabled;
            this.retries = retries;
        }
    }

    public static class IgnoredReplayService {
        private String orderId;
        private Input input;
        private Integer attempts;

        public void replay(String orderId, Input input, Integer attempts) {
            this.orderId = orderId;
            this.input = input;
            this.attempts = attempts;
        }
    }

    public static class PrimitiveIgnoredReplayService {
        public void replay(int attempts) {
        }
    }

    public static class MissingReplayService {
        public void replay(String value) {
        }
    }

    public static class GenericReplayService {
        private Level level;
        private char grade;
        private List<Input> inputs = new ArrayList<Input>();

        public void replay(Level level, char grade, List<Input> inputs) {
            this.level = level;
            this.grade = grade;
            this.inputs = inputs;
        }
    }

    public static class NamedReplayService implements ReplayContract {
        private String lastOrderId;

        public NamedReplayService(String name) {
        }

        @Override
        public void replay(String orderId) {
            this.lastOrderId = orderId;
        }
    }

    public static class Input {
        private String value;
    }
}
