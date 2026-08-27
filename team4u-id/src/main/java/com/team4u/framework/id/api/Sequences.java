package com.team4u.framework.id.api;

import java.util.Map;

/**
 * 序号生成门面
 * <p>
 * 一个规则标识对应一条 JSON 规则（配置键 {@code seq.{name}}，见规则配置），
 * 内部组装「配置驱动规则加载 → 分组策略 → KvStore 计数能力 → 本地号段」，
 * 调用方只面对取号本身。实现见 {@code core.SequenceService}。
 * </p>
 * 语义约定：
 * <ul>
 *     <li>规则缺失或非法：抛 {@link SeqConfigException}（快速失败）</li>
 *     <li>序号耗尽（设置了 {@code maxValue} 且未循环）：{@link #tryNext} 返回
 *     {@code null}，{@link #next} 抛 {@link SeqExhaustedException}</li>
 *     <li>存储故障：抛 kv 组件的 {@code KvStoreException}，与「无可用序号」严格区分</li>
 * </ul>
 *
 * @author jay.wu
 */
public interface Sequences {

    /**
     * 生成下一个序号
     *
     * @throws SeqConfigException     规则缺失或非法
     * @throws SeqExhaustedException 序号已耗尽
     */
    long next(String name);

    /**
     * 生成下一个序号，扩展属性供分组策略使用（如按商户分组）
     *
     * @see #next(String)
     */
    long next(String name, Map<String, Object> ext);

    /**
     * 生成下一个序号，额度语义
     *
     * @return 序号；已耗尽返回 {@code null}
     * @throws SeqConfigException 规则缺失或非法
     */
    Long tryNext(String name);

    /**
     * 生成下一个序号，额度语义，扩展属性供分组策略使用
     *
     * @see #tryNext(String)
     */
    Long tryNext(String name, Map<String, Object> ext);

    /**
     * 生成下一个序号并按规则模板渲染为字符串
     * <p>
     * 模板变量：{@code ${name}}（规则标识）、{@code ${group}}（分组标识）、
     * {@code ${seq}}（序号，按 {@code seqLength} 补零）。未配置模板时返回补零序号。
     * </p>
     *
     * @throws SeqConfigException     规则缺失或非法
     * @throws SeqExhaustedException 序号已耗尽
     */
    String nextFormatted(String name);

    /**
     * 生成下一个序号并渲染为字符串，扩展属性供分组策略使用
     *
     * @see #nextFormatted(String)
     */
    String nextFormatted(String name, Map<String, Object> ext);
}
