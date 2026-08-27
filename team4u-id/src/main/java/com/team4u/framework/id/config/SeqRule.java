package com.team4u.framework.id.config;

import com.team4u.framework.id.group.SeqGroupConfig;
import lombok.Data;

/**
 * 序号规则：一条 JSON 描述一个序号如何生成
 * <p>
 * 配置键为 {@code seq.{规则标识}}，经配置组件集中管理与热更新。
 * 序号取值遵循等差数列：{@code start, start+step, start+2*step, ...}，
 * 计数位置由存储的原子计数器维护，耗尽与循环在取号层以纯算术换算，
 * 不回写存储。
 * </p>
 *
 * @author jay.wu
 */
@Data
public class SeqRule {

    /**
     * 存储名（{@code SeqStores} 注册名）；缺省使用服务构造时传入的默认存储
     */
    private String store;

    /**
     * 分组配置；缺省不分组（所有请求共享同一计数器）
     */
    private SeqGroupConfig group;

    /**
     * 初始值
     */
    private long start = 1L;

    /**
     * 步进：相邻两个序号的差值
     */
    private int step = 1;

    /**
     * 最大值（含端点）；达到后耗尽（或循环）。缺省不设上限
     */
    private Long maxValue;

    /**
     * 达到最大值后是否从 start 重新循环
     */
    private boolean recycle;

    /**
     * 本地号段长度：大于 0 时启用本地号段（一次批量取 segment 个序号缓存本地，
     * 存储访问量降低 segment 倍）；0 表示每次取号直连存储
     */
    private int segment;

    /**
     * 序号补零长度，如 6 时序号 42 输出 000042；0 表示不补零
     */
    private int seqLength;

    /**
     * 输出模板，变量：${name}（规则标识）、${group}（分组标识）、${seq}（序号），
     * 如 {@code ORD-${group}-${seq}}；缺省输出补零序号
     */
    private String format;
}
