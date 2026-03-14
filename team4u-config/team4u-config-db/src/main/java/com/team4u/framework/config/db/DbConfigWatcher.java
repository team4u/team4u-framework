package com.team4u.framework.config.db;

import com.team4u.framework.base.jdbc.JdbcUtil;
import com.team4u.framework.config.core.spi.ConfigWatcher;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于数据库轮询的配置变更监听器。
 * <p>
 * 通过周期性检测 {@code system_config} 表的 {@code update_time} 最大值，
 * 实现低开销的配置变更探测。
 * </p>
 *
 * @author team4u
 */
@Slf4j
public class DbConfigWatcher implements ConfigWatcher {

    /**
     * 默认轮询间隔（秒）
     */
    private static final int DEFAULT_INTERVAL_SECONDS = 5;

    /**
     * 数据库数据源
     */
    private final DataSource dataSource;

    /**
     * 数据库配置选项
     */
    private final DbConfigOptions options;

    /**
     * 轮询间隔（秒）
     */
    private final int intervalSeconds;
    private final AtomicLong failureCount = new AtomicLong();
    /**
     * 上次记录的最大时间戳
     */
    private volatile long lastMaxTimestamp = 0L;
    private volatile boolean baselineInitialized = false;
    /**
     * 定时任务线程池
     */
    private ScheduledExecutorService scheduler;
    private volatile String lastErrorMessage;

    /**
     * 构建 DB 配置监听器（默认间隔 5 秒）
     *
     * @param dataSource 数据库数据源
     */
    public DbConfigWatcher(DataSource dataSource) {
        this(dataSource, DEFAULT_INTERVAL_SECONDS);
    }

    /**
     * 构建 DB 配置监听器
     *
     * @param dataSource      数据库数据源
     * @param intervalSeconds 轮询间隔（秒）
     */
    public DbConfigWatcher(DataSource dataSource, int intervalSeconds) {
        this(dataSource, intervalSeconds, new DbConfigOptions());
    }

    /**
     * 构建 DB 配置监听器
     *
     * @param dataSource      数据库数据源
     * @param intervalSeconds 轮询间隔（秒）
     * @param options         数据库配置选项
     */
    public DbConfigWatcher(DataSource dataSource, int intervalSeconds, DbConfigOptions options) {
        this.dataSource = dataSource;
        this.intervalSeconds = intervalSeconds;
        this.options = options;
    }

    @Override
    public int priority() {
        return 0;
    }

    /**
     * 开启变更监听任务。
     *
     * @param changeSignal 变更通知信号回调
     */
    @Override
    public synchronized void watch(Runnable changeSignal) {
        stopScheduler();

        OptionalLong baseline = queryMaxTimestamp();
        if (baseline.isPresent()) {
            lastMaxTimestamp = baseline.getAsLong();
            baselineInitialized = true;
        } else {
            baselineInitialized = false;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "db-config-watcher");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleWithFixedDelay(
                () -> pollAndSignal(changeSignal),
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS
        );

        log.info("[DbConfigWatcher] Started, interval={}s", intervalSeconds);
    }

    /**
     * 停止监听任务并释放资源。
     */
    @Override
    public synchronized void destroy() {
        stopScheduler();
    }

    /**
     * 周期性探测任务。
     */
    private void pollAndSignal(Runnable changeSignal) {
        try {
            OptionalLong currentMaxResult = queryMaxTimestamp();
            if (!currentMaxResult.isPresent()) {
                return;
            }

            long currentMax = currentMaxResult.getAsLong();
            if (!baselineInitialized) {
                lastMaxTimestamp = currentMax;
                baselineInitialized = true;
                return;
            }

            if (currentMax > lastMaxTimestamp) {
                log.debug("[DbConfigWatcher] Database config changed ({} -> {})", lastMaxTimestamp, currentMax);
                lastMaxTimestamp = currentMax;
                changeSignal.run();
            }
        } catch (Exception e) {
            log.error("[DbConfigWatcher] Exception during polling", e);
        }
    }

    /**
     * 获取表内最大更新时间。
     *
     * @return 毫秒级时间戳
     */
    private OptionalLong queryMaxTimestamp() {
        try {
            String sql = "SELECT MAX(" + options.getUpdateTimeColumn() + ") AS max_time FROM " + options.getTableName();
            List<Map<String, Object>> rows = JdbcUtil.query(dataSource, sql);
            if (rows.isEmpty()) {
                return OptionalLong.of(0L);
            }

            Object maxTime = rows.get(0).get("max_time");
            if (maxTime == null) {
                return OptionalLong.of(0L);
            }

            // 映射数据库时间类型为毫秒级时间戳
            if (maxTime instanceof Timestamp) {
                return OptionalLong.of(((Timestamp) maxTime).getTime());
            }

            return OptionalLong.of(Long.parseLong(maxTime.toString()));
        } catch (SQLException e) {
            failureCount.incrementAndGet();
            lastErrorMessage = e.getMessage();
            log.error("[DbConfigWatcher] Failed to query max timestamp", e);
            return OptionalLong.empty();
        }
    }

    public long getFailureCount() {
        return failureCount.get();
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    private void stopScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            log.info("[DbConfigWatcher] Stopped");
        }
        scheduler = null;
    }
}
