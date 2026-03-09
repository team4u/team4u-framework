package com.team4u.framework.retry.proxy;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.retry.domain.store.InvocationRecoveryData;
import com.team4u.framework.retry.recovery.RecoveryContext;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class InvocationReplayTest {

    @Test
    public void testRecoverResolvesPrimitiveParameters() throws Exception {
        PrimitiveReplayService service = new PrimitiveReplayService();
        BeanManager.getInstance().registerBean(PrimitiveReplayService.class.getName(), service);

        InvocationReplay replay = new InvocationReplay();
        replay.recover(InvocationRecoveryData.builder()
                        .beanName(PrimitiveReplayService.class.getName())
                        .methodName("replay")
                        .argTypes(Arrays.asList("int", "long", "boolean", "java.lang.String"))
                        .argValues(Arrays.asList("3", "4", "true", "\"done\""))
                        .build(),
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
        replay.recover(InvocationRecoveryData.builder()
                        .beanName(MixedReplayService.class.getName())
                        .methodName("replay")
                        .argTypes(Arrays.asList("boolean", "java.lang.Integer"))
                        .argValues(Arrays.asList("false", "7"))
                        .build(),
                RecoveryContext.builder().taskId("task-2").attempt(1).build());

        Assert.assertFalse(service.enabled);
        Assert.assertEquals(Integer.valueOf(7), service.retries);
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
}
