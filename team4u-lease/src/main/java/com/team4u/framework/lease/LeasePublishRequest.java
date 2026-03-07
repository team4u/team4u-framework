package com.team4u.framework.lease;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发布任务的请求。
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LeasePublishRequest {

    @Builder.Default
    private final String namespace = "default";
    private final String queue;
    private final String taskType;
    private final String payload;
    private final long delayMillis;
    @Builder.Default
    private final int priority = 0;
    @Singular
    private final Map<String, String> attributes;

    public Map<String, String> getAttributes() {
        if (attributes == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
    }
}
