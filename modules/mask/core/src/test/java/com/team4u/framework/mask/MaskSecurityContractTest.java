package com.team4u.framework.mask;

import org.junit.Assert;
import org.junit.Test;

public class MaskSecurityContractTest {

    private static final String MOBILE = "13800138000";

    @Test
    public void unknownPolicyFailsClosedWithExactMessage() {
        try {
            FastMasker.mask(MOBILE, "DOES_NOT_EXIST");
            Assert.fail("Unknown mask policy must not return sensitive input");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("Unknown mask policy: DOES_NOT_EXIST", e.getMessage());
        }
    }

    @Test
    public void unknownPolicyIsRejectedBeforeNullValueShortCircuit() {
        try {
            FastMasker.mask(null, "DOES_NOT_EXIST");
            Assert.fail("Unknown mask policy must be validated before null value");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("Unknown mask policy: DOES_NOT_EXIST", e.getMessage());
        }
    }

    @Test
    public void unknownPolicyIsRejectedBeforeEmptyValueShortCircuit() {
        try {
            FastMasker.mask("", "DOES_NOT_EXIST");
            Assert.fail("Unknown mask policy must be validated before empty value");
        } catch (IllegalArgumentException e) {
            Assert.assertEquals("Unknown mask policy: DOES_NOT_EXIST", e.getMessage());
        }
    }

    @Test
    public void nullStringPolicyIsRejected() {
        assertIllegalPolicy(MOBILE, null);
    }

    @Test
    public void emptyStringPolicyIsRejected() {
        assertIllegalPolicy(MOBILE, "");
    }

    @Test
    public void whitespaceStringPolicyIsRejected() {
        assertIllegalPolicy(MOBILE, " ");
        assertIllegalPolicy(MOBILE, " \t\r\n");
    }

    @Test
    public void nullEnumPolicyIsRejected() {
        try {
            FastMasker.mask(MOBILE, (MaskType) null);
            Assert.fail("Null mask policy must be rejected");
        } catch (IllegalArgumentException e) {
            Assert.assertNotNull(e.getMessage());
        }
    }

    @Test
    public void onlyExplicitNoneReturnsOriginalValue() {
        Assert.assertEquals(MOBILE, FastMasker.mask(MOBILE, MaskType.NONE));
        Assert.assertEquals(MOBILE, FastMasker.mask(MOBILE, "NONE"));
    }

    private void assertIllegalPolicy(String value, String policy) {
        try {
            FastMasker.mask(value, policy);
            Assert.fail("Illegal mask policy must be rejected: " + policy);
        } catch (IllegalArgumentException e) {
            Assert.assertNotNull(e.getMessage());
        }
    }
}
