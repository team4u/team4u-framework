package com.team4u.framework.ratelimiter.config;

import lombok.Data;

/**
 * 限流规则（配置驱动，JSON 列表中的一个条目）
 * <p>
 * 一个检查点（配置键 {@code team4u.ratelimiter.{point}}）对应一组规则，
 * 按 {@link #priority} 升序依次执行（越小优先级越高，与策略组件 ContextPolicy 约定一致）、首拒即停。
 * {@code key} 支持 {@code ${variable}} 模板占位符，变量取自检查上下文
 * （Map 取值或 Bean 公有 getter），实现按用户/商户等维度独立计数。
 * </p>
 *
 * @author jay.wu
 */
@Data
public class RateLimitRule {

    /**
     * 规则标识（同检查点内唯一，参与计数键组成，不允许包含 ':'）
     */
    private String id;

    /**
     * 算法名：fixed-window / token-bucket / sliding-window / history-window（或自定义注册名）
     */
    private String algorithm;

    /**
     * 命名存储（注册于 {@code NamedKvStoreRegistry.global()}）；空 = 使用引擎默认存储。
     * 无状态算法（如 history-window）不解析存储
     */
    private String store;

    /**
     * 计数键模板（含 ${variable} 占位符）；空 = 以检查点为静态键（全检查点共享额度）
     */
    private String key;

    /**
     * 优先级，越小优先级越高、越先执行（与策略组件 ContextPolicy 约定一致：
     * 参考量级 HIGHEST=-2147483648 / HIGH=-1000 / NORMAL=0 / LOW=1000 / LOWEST=2147483647）；
     * 同优先级保持配置顺序（稳定排序）
     */
    private int priority = 0;

    /**
     * 窗口时长（毫秒）。语义随算法：fixed-window 计数窗口、token-bucket 注满一桶时间、
     * sliding-window 滚动窗口长度、history-window 对齐窗口长度
     */
    private long windowMillis;

    /**
     * 阈值。语义随算法：fixed-window/sliding-window/history-window 窗口内请求数上限、
     * token-bucket 桶容量
     */
    private long threshold;

    /**
     * 存储故障时是否放行（fail-open）：true 放行本条并继续后续规则，false 立即拒绝
     */
    private boolean failOpen = true;

    /**
     * 算法私有配置（对象形态随算法声明，见 {@code RateLimitAlgorithm#configType()}）。
     * <p>
     * 加载期反序列化为算法声明的类型化实例并写回本字段，运行期只读；
     * 算法未声明配置类型（{@code Void}）时出现本字段将在加载期报错。
     * 如 history-window 的 {@code {"path": "client.history"}}。
     * </p>
     */
    private Object config;
}
