package com.team4u.framework.lease.model;

import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.enums.LeaseTaskOutcome;
import org.junit.Assert;
import org.junit.Test;

public class LeaseCloseRequestTest {

    @Test
    public void testNormalizeForRuntimeRejectsNullOutcome() {
        try {
            LeaseCloseRequest.builder().build().normalizeForRuntime();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("outcome"));
        }
    }

    @Test
    public void testNormalizeForRuntimeRejectsFailedWithoutReason() {
        try {
            LeaseCloseRequest.builder()
                    .outcome(LeaseTaskOutcome.FAILED)
                    .build()
                    .normalizeForRuntime();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("failureReason"));
        }
    }

    @Test
    public void testNormalizeRejectsNonFailedOutcomeWithFailureReason() {
        try {
            LeaseCloseRequest.builder()
                    .outcome(LeaseTaskOutcome.SUCCEEDED)
                    .failureReason(LeaseTaskFailureReason.HANDLER_EXCEPTION)
                    .build()
                    .normalizeForRuntime();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue(ex.getMessage().contains("failureReason"));
        }
    }

    @Test
    public void testNormalizeForAdminDefaultsManualFailReason() {
        LeaseCloseRequest request = LeaseCloseRequest.builder()
                .outcome(LeaseTaskOutcome.FAILED)
                .errorMessage("manual")
                .build()
                .normalizeForAdmin();

        Assert.assertEquals(LeaseTaskOutcome.FAILED, request.getOutcome());
        Assert.assertEquals(LeaseTaskFailureReason.MANUAL_FAIL, request.getFailureReason());
        Assert.assertEquals("manual", request.getErrorMessage());
    }
}
