package com.team4u.framework.config.core.support;

import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.serializer.json.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON 配置仓库模板
 * <p>
 * 收编各模块「配置驱动单例仓库」重复的 init/stop/解析骨架：
 * 从 {@link ConfigManager} 读取指定配置键的 JSON 内容，反序列化为目标类型，
 * 成功后原子替换内部引用，并挂载配置监听实现热更新。
 * <p>
 * <b>统一降级语义（标准）</b>：
 * <ul>
 *     <li>首次加载失败：直接抛出异常（启动期快速失败，避免带病上线）</li>
 *     <li>后续热更新失败：保留旧配置并打印 warn 日志（服务连续性优先）</li>
 *     <li>配置为空 / 被删除：回退到 {@link #emptyConfig()} 提供的缺省值</li>
 * </ul>
 * 该语义综合了历史上三个实现（解析失败返回 null / 抛异常 / 保留旧配置）中最合理的部分，
 * 作为所有 JSON 配置仓库的统一标准。
 * <p>
 * 子类通过模板方法提供差异部分：
 * <ul>
 *     <li>{@link #configKey()}：配置中心的配置键（必须实现）</li>
 *     <li>{@link #typeReference()}：目标类型引用；提供后模板自动按
 *         {@link JsonUtil#toBean(String, TypeReference, boolean)} 反序列化，
 *         需要自定义解析/校验逻辑的场景改为覆写 {@link #parseJson(String)}</li>
 *     <li>{@link #emptyConfig()}：空/缺失配置的缺省值（默认 null）</li>
 *     <li>{@link #onConfigLoaded(Object, Object)}：配置成功加载（含热更新）后的变更回调（可选）</li>
 * </ul>
 * 线程模型：init/stop 互斥同步；{@link #get()} 无锁读取 volatile 引用；
 * {@link #onConfigLoaded(Object, Object)} 在配置变更线程回调，实现方需自行保证线程安全。
 *
 * @param <T> 配置对象类型
 * @author jay.wu
 */
public abstract class AbstractJsonConfigRepository<T> {

    /**
     * 日志器（以子类实际类型命名，便于区分各仓库的日志来源）
     */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * 当前生效的配置快照
     * <p>
     * volatile 保证热更新成功后的原子替换对所有读线程立即可见。
     */
    private volatile T config;

    /**
     * 配置监听句柄（null 表示尚未初始化或已停止）
     */
    private volatile AutoCloseable listenerHandle;

    /**
     * 构造模板仓库，初始配置为 {@link #emptyConfig()}
     * <p>
     * 注意：构造阶段会调用 {@link #emptyConfig()}，其实现不应依赖子类实例字段。
     */
    protected AbstractJsonConfigRepository() {
        this.config = emptyConfig();
    }

    /**
     * @return 配置中心的配置键
     */
    protected abstract String configKey();

    /**
     * @return 配置目标的类型引用；返回 null 表示子类自行覆写 {@link #parseJson(String)}
     */
    protected TypeReference<T> typeReference() {
        return null;
    }

    /**
     * 解析 JSON 配置内容
     * <p>
     * 默认按 {@link #typeReference()} 提供的类型引用反序列化；
     * 解析失败直接抛出异常，由模板统一实现在首次加载时快速失败、热更新时保留旧配置。
     * 需要额外校验或加工（如表达式预编译、逐条过滤）的场景请覆写本方法。
     *
     * @param json 原始 JSON 字符串（保证非空白）
     * @return 解析后的配置对象，不可为 null
     * @throws Exception 解析失败
     */
    protected T parseJson(String json) throws Exception {
        TypeReference<T> typeReference = typeReference();
        if (typeReference == null) {
            throw new UnsupportedOperationException(
                    "子类必须提供 typeReference() 或覆写 parseJson(String)，configKey=" + configKey());
        }
        T value = JsonUtil.toBean(json, typeReference, false);
        return value != null ? value : emptyConfig();
    }

    /**
     * @return 配置为空 / 缺失 / 被删除时的缺省值；默认 null。实现不应依赖子类实例字段
     */
    protected T emptyConfig() {
        return null;
    }

    /**
     * 配置成功加载（含首次加载与热更新）后的变更回调
     *
     * @param oldValue 替换前的配置
     * @param newValue 替换后的配置
     */
    protected void onConfigLoaded(T oldValue, T newValue) {
    }

    /**
     * 初始化仓库：同步加载初始配置并挂载配置监听
     * <p>
     * 首次加载失败将直接抛出异常（启动期快速失败），且不留监听残留；
     * 重复调用会先释放旧的监听与状态，支持底层配置管理器热切换。
     *
     * @param configManager 配置管理器
     */
    public synchronized void init(ConfigManager configManager) {
        stop();
        // 首次同步加载：解析失败抛异常，交由调用方决定启动失败处理
        apply(configManager.getString(configKey()).orElse(null));
        // 加载成功后才挂载监听，保证监听线程总能读到已就绪的配置
        this.listenerHandle = configManager.registerChangeListener(configKey(), this::onConfigChanged);
    }

    /**
     * 停止仓库：注销配置监听并重置为缺省配置（幂等，可重复调用）
     * <p>
     * 重置同样经由 {@link #onConfigLoaded(Object, Object)} 通知子类，
     * 保证派生快照（如染色规则列表）与仓库状态同步归零。
     */
    public synchronized void stop() {
        AutoCloseable handle = this.listenerHandle;
        this.listenerHandle = null;
        if (handle != null) {
            try {
                handle.close();
            } catch (Exception e) {
                log.warn("AbstractJsonConfigRepository|closeListener|fail|key={}|msg={}",
                        configKey(), e.getMessage());
            }
        }
        T previous = this.config;
        this.config = emptyConfig();
        // 仅在本次 stop 实际释放了监听（即仓库之前处于运行态）时才通知子类重置，
        // 避免重复 stop / 构造后的首次 stop 产生冗余回调
        if (handle != null) {
            onConfigLoaded(previous, this.config);
        }
    }

    /**
     * @return 仓库是否已完成初始化（init 成功且未被 stop）
     */
    public boolean isInitialized() {
        return listenerHandle != null;
    }

    /**
     * 获取当前生效的配置
     *
     * @return 当前配置快照；未初始化时为 {@link #emptyConfig()}
     */
    public T get() {
        return config;
    }

    /**
     * 直接替换当前配置快照（测试或嵌入式场景专用，绕过配置中心）
     *
     * @param config 新配置；null 时回退为缺省值
     */
    protected void replaceConfig(T config) {
        this.config = config != null ? config : emptyConfig();
    }

    /**
     * 应用配置内容：解析/回退成功后原子替换引用并触发回调
     */
    private void apply(String json) {
        T next = (json == null || json.trim().isEmpty())
                ? emptyConfig()
                : parseUnchecked(json);
        T previous = this.config;
        this.config = next;
        onConfigLoaded(previous, next);
    }

    /**
     * 执行解析并包装异常（保留原始原因；子类已表达的语义化异常原样向上传播）
     * <p>
     * 包装为 IllegalStateException 仅适用于「裸解析失败」（如 JSON 语法错误、
     * 缺失 typeReference 等）；若子类在 parseJson 中已抛出带业务语义的异常
     * （如 mask-config 的 IllegalArgumentException fail-closed 契约），
     * 原样向上传播，避免破坏子类对外异常契约。
     */
    private T parseUnchecked(String json) {
        try {
            return parseJson(json);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // 子类语义化异常（含模板自身 UnsupportedOperations 转译后的失败）直接传播
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("配置解析失败: key=" + configKey()
                    + ", json=" + abbreviate(json), e);
        }
    }

    /**
     * 日志用的配置内容缩略（避免异常信息刷屏）
     */
    private String abbreviate(String json) {
        if (json == null) {
            return null;
        }
        return json.length() <= 200 ? json : json.substring(0, 200) + "...";
    }

    /**
     * 配置变更回调：统一降级语义——热更新失败保留旧配置并打印 warn 日志
     */
    private void onConfigChanged(String key, String oldValue, String newValue) {
        try {
            apply(newValue);
        } catch (Exception e) {
            log.warn("AbstractJsonConfigRepository|hotReload|fail|keepOldConfig|key={}|msg={}",
                    configKey(), e.getMessage(), e);
        }
    }
}
