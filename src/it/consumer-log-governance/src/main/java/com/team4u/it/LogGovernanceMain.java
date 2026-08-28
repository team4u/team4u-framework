package com.team4u.it;

import com.team4u.framework.log.LogBootstrap;
import com.team4u.framework.log.core.LogEngine;
import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.support.TestLogHelper;
import com.team4u.framework.serializer.json.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;

public class LogGovernanceMain {

    public static void main(String[] args) {
        TestLogHelper helper = null;
        LogEngine originalEngine = null;
        try {
            Map<String, String> input = new LinkedHashMap<>();
            input.put("provider", "jackson");
            Map<String, String> output = JsonUtil.toBean(JsonUtil.toJsonStr(input), Map.class);
            if (output == null || output.isEmpty() || !"jackson".equals(output.get("provider"))) {
                throw new IllegalStateException("Runtime JSON provider roundtrip failed: " + output);
            }

            originalEngine = LogEngine.getInstance();
            helper = TestLogHelper.start();
            originalEngine.processAndOutput(event("before-governance"));
            if (helper.lastEvent() == null || !"before-governance".equals(helper.lastEvent().getAction())) {
                throw new IllegalStateException("Original core engine did not emit an event");
            }
            requireNonJson(helper.lastJson(), "original core engine");

            LogBootstrap.start();
            LogEngine installedEngine = LogEngine.getInstance();
            if (installedEngine == originalEngine) {
                throw new IllegalStateException("LogBootstrap did not install a governance engine");
            }

            installedEngine.processAndOutput(event("ConsumerGovernance"));
            String governed = installedEngine.toJson(event("governance"));
            requireJson(governed, "governance", "governance engine");
            if (helper.lastEvent() == null || !"ConsumerGovernance".equals(helper.lastEvent().getAction())) {
                throw new IllegalStateException("Governance consumer did not emit an event");
            }
            requireJson(helper.lastJson(), "ConsumerGovernance", "active serializer");
        } finally {
            try {
                LogBootstrap.stop();
            } finally {
                if (helper != null) {
                    helper.stop();
                }
                if (originalEngine != null) {
                    LogEngine current = LogEngine.getInstance();
                    if (current != originalEngine) {
                        LogEngine.restore(current, originalEngine);
                    }
                    String restored = originalEngine.toJson(event("after-governance"));
                    requireNonJson(restored, "restored core engine");
                }
            }
        }
    }

    private static LogEvent event(String action) {
        return new LogEvent()
                .setLoggerName(LogGovernanceMain.class.getName())
                .setAction(action)
                .put("provider", "jackson");
    }

    private static void requireJson(String value, String action, String source) {
        if (value == null || !value.startsWith("{") || !value.endsWith("}")
                || !value.contains("\"action\":\"" + action + "\"")) {
            throw new IllegalStateException(source + " did not serialize JSON: " + value);
        }
    }

    private static void requireNonJson(String value, String source) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(source + " produced empty output");
        }
        if (value.trim().startsWith("{") || value.contains("\"action\":\"")) {
            throw new IllegalStateException(source + " unexpectedly produced JSON: " + value);
        }
    }
}
