package com.team4u.framework.kv;

/**
 * 键空间中的键唯一标识：键空间名 + 键名
 * <p>
 * 键空间（space）用于多业务、多场景的数据隔离，同一存储可承载多个键空间。
 * 完整标识为 {@code space:key}，因此 space 与 key 均不允许包含分隔符 {@code :}
 * （Redis 等存储以 {@code toString()} 作为物理键，需保证编解码无歧义）。
 * </p>
 * <p>
 * 实例不可变，hash 在构造时预计算——每次 L1 缓存查找都会调用
 * {@link #hashCode()}，避免热路径上的重复分配。
 * </p>
 *
 * @author jay.wu
 */
public final class SpaceKey {

    private static final char SEPARATOR = ':';

    private final String space;
    private final String key;
    private final int hash;

    private SpaceKey(String space, String key) {
        if (invalid(space)) {
            throw new IllegalArgumentException("space cannot be null, empty, blank or contain ':'");
        }
        if (invalid(key)) {
            throw new IllegalArgumentException("key cannot be null, empty, blank or contain ':'");
        }
        this.space = space;
        this.key = key;
        this.hash = 31 * space.hashCode() + key.hashCode();
    }

    private static boolean invalid(String s) {
        return s == null || s.isEmpty() || s.trim().isEmpty() || s.indexOf(SEPARATOR) >= 0;
    }

    public static SpaceKey of(String space, String key) {
        return new SpaceKey(space, key);
    }

    public String getSpace() {
        return space;
    }

    public String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SpaceKey spaceKey = (SpaceKey) o;
        return hash == spaceKey.hash && space.equals(spaceKey.space) && key.equals(spaceKey.key);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return space + SEPARATOR + key;
    }
}
