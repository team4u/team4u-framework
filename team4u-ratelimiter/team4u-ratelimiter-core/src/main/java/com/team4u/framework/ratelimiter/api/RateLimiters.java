package com.team4u.framework.ratelimiter.api;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.ratelimiter.core.RateLimitEngine;

import java.time.Clock;
import java.util.Objects;

/**
 * 限流静态门面：默认全局引擎的持有与生命周期入口
 * <p>
 * 组装方式：{@code init(ConfigManager, KvStore[, Clock])} 显式初始化；
 * 未 init 时首次调用以 {@code ConfigManager.global()} + 内存存储懒加载默认引擎。
 * 引擎引用 volatile + 双检锁保证并发安全，{@code destroy()} 复位引用供测试隔离。
 * </p>
 * <p>
 * {@code acquire} 返回完整裁决结果（拒绝不抛异常，{@code allowed=false} 携带
 * 拒绝规则与重试等待等信息）；{@code tryAcquire} 仅返回是否放行。
 * </p>
 *
 * @author jay.wu
 */
public final class RateLimiters {

    private static volatile RateLimitEngine engine;

    private RateLimiters() {
    }

    /**
     * 初始化全局引擎（替换既有引擎并释放其资源）
     */
    public static void init(ConfigManager configManager, KvStore store) {
        init(configManager, store, Clock.systemUTC());
    }

    /**
     * 初始化全局引擎（可注入时钟，供测试虚拟推进）
     */
    public static synchronized void init(ConfigManager configManager, KvStore store, Clock clock) {
        Objects.requireNonNull(configManager, "configManager");
        Objects.requireNonNull(store, "store");
        destroy();
        engine = new RateLimitEngine(configManager, store, clock);
    }

    /**
     * 限流检查：返回裁决结果（与引擎同构，拒绝不抛异常——「否」是判定服务的正常产出，
     * 携带完整信息；何时抛、抛什么由调用方决定，注解代理的 {@code RateLimitException}
     * 是穿越方法签名边界的传输手段，编程式路径不沿用）
     */
    public static RateLimitResult acquire(String point, Object context) {
        return acquire(point, context, 1);
    }

    /**
     * 限流检查（指定许可数）：返回裁决结果，拒绝不抛异常
     */
    public static RateLimitResult acquire(String point, Object context, int permits) {
        return engine().acquire(point, context, permits);
    }

    /**
     * 限流检查：仅返回是否放行，不抛限流异常
     */
    public static boolean tryAcquire(String point, Object context) {
        return engine().acquire(point, context, 1).isAllowed();
    }

    /**
     * 销毁全局引擎并复位引用（复位后下次调用重新懒加载默认引擎）
     */
    public static synchronized void destroy() {
        RateLimitEngine current = engine;
        if (current != null) {
            current.destroy();
            engine = null;
        }
    }

    /**
     * 当前引擎：未初始化时懒加载默认引擎（全局配置 + 内存存储）
     */
    private static RateLimitEngine engine() {
        RateLimitEngine current = engine;
        if (current != null) {
            return current;
        }
        synchronized (RateLimiters.class) {
            current = engine;
            if (current == null) {
                current = new RateLimitEngine(ConfigManager.global(), new InMemoryKvStore());
                engine = current;
            }
            return current;
        }
    }
}
