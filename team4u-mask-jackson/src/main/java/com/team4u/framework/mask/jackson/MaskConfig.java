package com.team4u.framework.mask.jackson;

/**
 * Mask module serialization configuration.
 */
public class MaskConfig {

    /**
     * Attribute key used to carry this configuration through a Jackson serialization context.
     */
    public static final String ATTR_KEY = "team4u.mask.config";

    /**
     * Maximum output string length. Values less than or equal to zero disable truncation.
     */
    private int maxStringLength = -1;

    public int getMaxStringLength() {
        return maxStringLength;
    }

    public MaskConfig setMaxStringLength(int maxStringLength) {
        this.maxStringLength = maxStringLength;
        return this;
    }
}
