package com.team4u.framework.kv;

import java.util.Objects;

/**
 * 键空间中的键唯一标识：键空间名 + 键名
 * <p>
 * 键空间（space）用于多业务、多场景的数据隔离，同一存储可承载多个键空间。
 * 完整标识为 {@code space:key}。
 * </p>
 */
public final class SpaceKey {

    private final String space;
    private final String key;

    private SpaceKey(String space, String key) {
        if (space == null || space.isEmpty()) {
            throw new IllegalArgumentException("space cannot be null or empty");
        }
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key cannot be null or empty");
        }
        this.space = space;
        this.key = key;
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
        return space.equals(spaceKey.space) && key.equals(spaceKey.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(space, key);
    }

    @Override
    public String toString() {
        return space + ":" + key;
    }
}
