package com.team4u.log.config;

import cn.hutool.json.JSONUtil;
import cn.hutool.log.Log;
import com.team4u.framework.base.config.StringConfigParser;

/**
 * 日志模块动态配置解析器
 * <p>
 * 用于将配置中心下发的 JSON 文本解析为 {@link LogDynamicConfig} 对象。
 */
public class LogConfigParser implements StringConfigParser<LogDynamicConfig> {

    private static final Log log = Log.get();

    @Override
    public LogDynamicConfig parse(String configContent) {
        if (configContent == null || configContent.trim().isEmpty()) {
            return new LogDynamicConfig();
        }
        try {
            // 解析 JSON 文本为 LogDynamicConfig 对象
            return JSONUtil.toBean(configContent, LogDynamicConfig.class);
        } catch (Exception e) {
            log.error("LogConfigParser|parse|fail|msg={}", e.getMessage());
            return new LogDynamicConfig();
        }
    }
}
