package com.team4u.framework.kv;

/**
 * 键值变更事件
 *
 * @author jay.wu
 */
@lombok.Getter
public class KvEvent {

    /**
     * 事件类型
     */
    public enum Type {

        /**
         * 写入（新增或覆盖，含续期导致的过期时间变化）
         */
        PUT,

        /**
         * 删除（含过期淘汰）
         */
        REMOVE
    }

    private final Type type;
    private final SpaceKey key;
    private final String newValue;

    public KvEvent(Type type, SpaceKey key, String newValue) {
        this.type = type;
        this.key = key;
        this.newValue = newValue;
    }

    @Override
    public String toString() {
        return "KvEvent{type=" + type + ", key=" + key + "}";
    }
}
