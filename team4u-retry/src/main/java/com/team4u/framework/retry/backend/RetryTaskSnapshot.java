package com.team4u.framework.retry.backend;

import cn.hutool.json.JSONUtil;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 重试任务快照
 * <p>
 * 包含了跨节点还原方法调用所需的所有元数据。
 *
 * @author jay.wu
 */
@Getter
@Setter
public class RetryTaskSnapshot {
    /**
     * Bean 标识符（对应 BeanManager 中的名称或类全限定名）
     */
    private String beanName;
    /**
     * 方法名
     */
    private String methodName;
    /**
     * 参数类型全限定名列表
     */
    private List<String> argTypes;
    /**
     * 参数 JSON 序列化后的字符串列表
     */
    private List<String> argJsonValues;
    /**
     * 全局累计尝试次数
     */
    private int globalAttempt;

    /**
     * 转换为 JSON 字符串用于持久化存储
     */
    public String toJson() {
        return JSONUtil.toJsonStr(this);
    }
}
