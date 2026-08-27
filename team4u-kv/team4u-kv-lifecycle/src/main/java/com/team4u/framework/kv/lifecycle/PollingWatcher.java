package com.team4u.framework.kv.lifecycle;

import com.team4u.framework.kv.KvEvent;
import com.team4u.framework.kv.KvListener;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStores;
import com.team4u.framework.kv.ScanCapable;
import com.team4u.framework.kv.SpaceKey;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 轮询订阅：为不支持原生通知的存储提供 {@code WatchCapable} 的降级实现
 * <p>
 * 基于 {@link ScanCapable#scan(String)} 周期对比快照差异，产生
 * {@link KvEvent.Type#PUT} / {@link KvEvent.Type#REMOVE} 事件。
 * 轮询周期即事件延迟上界；扫描成本较高，适合键量可控的键空间
 * （如任务结果等待、配置型数据）。
 * </p>
 * <p>
 * 与 {@code InMemoryKvStore} 的原生 watch 相比：只能发现「两次轮询之间」的
 * 最终状态，同键多次变更会合并为一次事件。
 * 支持装饰过的存储（构造期自动沿装饰链解析内层 ScanCapable，见 {@link KvStores}）；
 * 轮询的 scan/get 直达解析后的底层存储，保证读取新鲜度（不被 L1 缓存延迟）。
 * </p>
 *
 * @author jay.wu
 */
@Slf4j
public class PollingWatcher implements AutoCloseable {


    private final KvStore store;
    private final long pollIntervalMillis;
    private final Map<String, List<KvListener>> listeners = new ConcurrentHashMap<>();
    private final Map<String, Map<SpaceKey, String>> snapshots = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private final Thread poller;

    public PollingWatcher(KvStore store, long pollIntervalMillis) {
        ScanCapable resolved = KvStores.capabilityOf(store, ScanCapable.class);
        if (resolved == null) {
            throw new IllegalArgumentException(
                    "PollingWatcher requires a ScanCapable store, got: "
                            + store.getClass().getName() + "（含装饰链解析）");
        }
        // 轮询的 scan 与 get 直达解析后的底层存储：经 L1 缓存读取会把事件延迟到缓存 TTL 之后
        this.store = (KvStore) resolved;
        this.pollIntervalMillis = pollIntervalMillis;
        this.poller = new Thread(this::pollLoop, "kv-polling-watcher");
        this.poller.setDaemon(true);
        this.poller.start();
    }

    /**
     * 订阅指定键空间的变更
     *
     * @return 关闭句柄，取消订阅
     */
    public AutoCloseable watch(String space, KvListener listener) {
        if (!running) {
            throw new IllegalStateException("PollingWatcher already closed");
        }
        List<KvListener> list =
                listeners.computeIfAbsent(space, k -> new CopyOnWriteArrayList<>());
        list.add(listener);
        // 首次订阅以空快照开始，只推送订阅后的增量
        snapshots.computeIfAbsent(space, k -> new ConcurrentHashMap<>());
        return () -> {
            list.remove(listener);
            if (list.isEmpty()) {
                // 最后一个订阅者退出后回收该键空间的注册项与快照
                listeners.remove(space, list);
                snapshots.remove(space);
            }
        };
    }

    private void pollLoop() {
        while (running) {
            try {
                pollOnce();
            } catch (RuntimeException e) {
                log.warn("Polling failed", e);
            }
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pollOnce() {
        for (Map.Entry<String, List<KvListener>> entry : listeners.entrySet()) {
            String space = entry.getKey();
            if (entry.getValue().isEmpty()) {
                continue;
            }
            Map<SpaceKey, String> last = snapshots.get(space);
            Map<SpaceKey, String> current = scanCurrent(space);

            for (Map.Entry<SpaceKey, String> e : current.entrySet()) {
                String oldValue = last.get(e.getKey());
                if (!e.getValue().equals(oldValue)) {
                    fire(entry.getValue(), new KvEvent(KvEvent.Type.PUT, e.getKey(), e.getValue()));
                }
            }
            for (SpaceKey key : last.keySet()) {
                if (!current.containsKey(key)) {
                    fire(entry.getValue(), new KvEvent(KvEvent.Type.REMOVE, key, null));
                }
            }
            last.clear();
            last.putAll(current);
        }
    }

    private Map<SpaceKey, String> scanCurrent(String space) {
        List<SpaceKey> keys = ((ScanCapable) store).scan(space);
        Map<SpaceKey, String> current = new HashMap<>();
        for (SpaceKey key : keys) {
            KvRecord record = store.get(key);
            if (record != null) {
                current.put(key, record.getValue());
            }
        }
        return current;
    }

    private void fire(List<KvListener> list, KvEvent event) {
        for (KvListener listener : list) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.warn("KvListener failed|event={}", event, e);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        poller.interrupt();
        listeners.clear();
        snapshots.clear();
    }
}
