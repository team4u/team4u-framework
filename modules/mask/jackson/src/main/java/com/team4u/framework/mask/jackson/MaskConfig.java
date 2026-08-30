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

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MaskConfig)) {
            return false;
        }
        MaskConfig other = (MaskConfig) obj;
        return other.canEqual(this) && maxStringLength == other.maxStringLength;
    }

    @Override
    public int hashCode() {
        return 59 + Integer.hashCode(maxStringLength);
    }

    @Override
    public String toString() {
        return "MaskConfig(maxStringLength=" + maxStringLength + ")";
    }

    protected boolean canEqual(Object obj) {
        return obj instanceof MaskConfig;
    }
}
