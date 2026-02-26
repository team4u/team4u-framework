package com.team4u.framework.config.db;

import cn.hutool.db.Db;
import cn.hutool.db.Entity;
import com.team4u.framework.config.core.spi.ConfigWatcher;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 基于数据库轮询的配置变更监听器。
 * <p>
 * 通过周期性检测 {@code system_config} 表的 {@code update_time} 最大值，
 * 实现低开销的配置变更探测。
 * </p>
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

    /**
     * 上次记录的最大时间戳
     */
    private volatile long lastMaxTimestamp = 0L;

    /**
     * 定时任务线程池
     */
    private ScheduledExecutorService scheduler;

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
    public void watch(Runnable changeSignal) {
        // 初始化基准时间戳
        lastMaxTimestamp = queryMaxTimestamp();

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
    public void destroy() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            log.info("[DbConfigWatcher] Stopped");
        }
    }

    /**
     * 周期性探测任务。
     */
    private void pollAndSignal(Runnable changeSignal) {
        try {
            long currentMax = queryMaxTimestamp();
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
    private long queryMaxTimestamp() {
        try {
            String sql = "SELECT MAX(" + options.getUpdateTimeColumn() + ") AS max_time FROM " + options.getTableName();
            List<Entity> rows = Db.use(dataSource).query(sql);
            if (rows.isEmpty()) {
                return 0L;
            }

            Object maxTime = rows.get(0).get("max_time");
            if (maxTime == null) {
                return 0L;
            }

            // 映射数据库时间类型为毫秒时间戳
            if (maxTime instanceof java.sql.Timestamp) {
                return ((java.sql.Timestamp) maxTime).getTime();
            }

            return Long.parseLong(maxTime.toString());
        } catch (SQLException e) {
            log.error("[DbConfigWatcher] Failed to query max timestamp", e);
            return 0L;
        }
    }
}
