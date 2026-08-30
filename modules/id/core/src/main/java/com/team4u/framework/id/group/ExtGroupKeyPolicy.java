package com.team4u.framework.id.group;

import com.team4u.framework.id.api.SeqConfigException;

/**
 * 调用上下文分组策略（内置）
 * <p>
 * 分组标识直接取调用方透传的扩展属性（{@code next(name, ext)}），适合
 * 按商户、渠道等业务维度隔离计数。缺少 {@code extKey} 配置或上下文缺少
 * 对应属性时快速失败，避免所有调用方静默落到同一计数器。
 * </p>
 *
 * @author jay.wu
 */
public class ExtGroupKeyPolicy implements GroupKeyPolicy {

    public static final String KEY = "EXT";

    public static final ExtGroupKeyPolicy INSTANCE = new ExtGroupKeyPolicy();

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String groupKey(Context context) {
        SeqGroupConfig config = context.getConfig();
        String extKey = config.getExtKey();
        if (extKey == null || extKey.trim().isEmpty()) {
            throw new SeqConfigException("EXT group requires extKey|rule=" + context.getRuleId());
        }
        Object value = context.getExt().get(extKey);
        if (value == null) {
            throw new SeqConfigException("EXT group key missing|rule=" + context.getRuleId()
                    + "|extKey=" + extKey);
        }
        return String.valueOf(value);
    }
}
