package com.team4u.framework.singleflight.api;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.singleflight.core.SingleFlightEngine;

import java.util.Objects;

/**
 * 全局静态门面：以最简入口暴露 {@link SingleFlightEngine} 的执行能力。
 * <p>
 * 引擎实例全局唯一（volatile + 双重检查）：显式 {@link #init} 用于注入指定配置与存储；
 * 未初始化即调用 {@link #execute} 时，以 {@code ConfigManager.global()} + 内存存储
 * 懒加载兜底。重复 init 会先销毁旧引擎再创建新引擎。
 * </p>
 *
 * @author jay.wu
 */
public final class SingleFlights {

    private static volatile SingleFlightEngine engine;

    private SingleFlights() {
    }

    /**
     * 以系统 UTC 时钟初始化全局引擎。
     */
    public static void init(ConfigManager configManager, KvStore store) {
        init(configManager, store, java.time.Clock.systemUTC());
    }

    /**
     * 初始化全局引擎：先销毁既有引擎（释放规则监听与锁管理器），再以新配置重建。
     * 测试场景可注入自定义 {@link Clock} 控制时间。
     */
    public static synchronized void init(ConfigManager configManager, KvStore store,
                                         java.time.Clock clock) {
        Objects.requireNonNull(configManager, "configManager");
        Objects.requireNonNull(store, "store");
        destroy();
        engine = new SingleFlightEngine(configManager, store, clock);
    }

    /**
     * 执行一次回源合并请求，语义与 {@link SingleFlightEngine#execute} 一致。
     */
    public static <T> T execute(SingleFlightExecution<T> execution) {
        return engine().execute(execution);
    }

    /**
     * 销毁全局引擎；未初始化时为空操作，可安全重复调用。
     */
    public static synchronized void destroy() {
        SingleFlightEngine current = engine;
        if (current != null) {
            current.destroy();
            engine = null;
        }
    }

    /**
     * 获取引擎实例：未显式 init 时以全局配置 + 内存存储懒加载（仅适合单进程场景，
     * 跨实例协调需在启动阶段显式 init 指向共享存储）。
     */
    private static SingleFlightEngine engine() {
        SingleFlightEngine current = engine;
        if (current != null) {
            return current;
        }
        synchronized (SingleFlights.class) {
            current = engine;
            if (current == null) {
                current = new SingleFlightEngine(ConfigManager.global(), new InMemoryKvStore());
                engine = current;
            }
            return current;
        }
    }
}
