package com.team4u.criterion.model.convert;

import cn.hutool.core.util.StrUtil;

/**
 * 版本转换器
 * <p>
 * 支持将字符串转换为可比较的版本对象
 *
 * @author jay.wu
 */
public class VersionValueConverter implements ValueConverter {

    @Override
    public String key() {
        return "version";
    }

    @Override
    public Comparable<?> apply(Object obj) {
        return obj == null ? null : new ComparableVersion(String.valueOf(obj));
    }

    /**
     * 简单的版本比较包装类
     */
    private static class ComparableVersion implements Comparable<ComparableVersion> {
        private final String v;

        public ComparableVersion(String v) {
            this.v = v;
        }

        @Override
        public int compareTo(ComparableVersion other) {
            if (other == null) {
                return 1;
            }
            return StrUtil.compareVersion(this.v, other.v);
        }

        @Override
        public String toString() {
            return v;
        }
    }
}
