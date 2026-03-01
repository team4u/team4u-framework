package com.team4u.log.config;

import cn.hutool.json.JSONUtil;
import com.team4u.framework.base.config.StringConfigParser;

/**
 * 日志模块动态配置解析器
 */
public class LogConfigParser implements StringConfigParser<LogDynamicConfig> {

    @Override
    public LogDynamicConfig parse(String configContent) {
        if (configContent == null || configContent.trim().isEmpty()) {
            return new LogDynamicConfig();
        }
        try {
            // 解析 JSON 文本为 LogDynamicConfig 对象
            return JSONUtil.toBean(configContent, LogDynamicConfig.class);
        } catch (Exception e) {
            System.err.println("[Team4u-Log] Failed to parse log dynamic config: " + e.getMessage());
            return new LogDynamicConfig();
        }
    }
}
