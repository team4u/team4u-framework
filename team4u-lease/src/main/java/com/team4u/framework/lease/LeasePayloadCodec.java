package com.team4u.framework.lease;

/**
 * 负载编解码扩展点。
 *
 * @param <T> 业务负载类型
 */
public interface LeasePayloadCodec<T> {

    String encode(T payload);

    T decode(String payload);

    static LeasePayloadCodec<String> stringCodec() {
        return new LeasePayloadCodec<String>() {
            @Override
            public String encode(String payload) {
                return payload;
            }

            @Override
            public String decode(String payload) {
                return payload;
            }
        };
    }
}
