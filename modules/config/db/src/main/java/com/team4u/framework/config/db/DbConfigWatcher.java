package com.team4u.framework.config.db;

import com.team4u.framework.base.jdbc.JdbcUtil;
import com.team4u.framework.base.refresh.RefreshableValue;
import com.team4u.framework.config.core.spi.ConfigWatcher;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 基于数据库轮询的配置变更监听器。
 * <p>
 * 内部基于 {@link RefreshableValue} 实现：缓存值为 {@code system_config} 表的
 * {@code MAX(update_time)} 毫秒时间戳，后台周期刷新，仅当最大值严格增大时触发变更信号
 * （首载建基线不触发）。查询失败计入失败计数并按冷却策略退避重试，不中断轮询。
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
     * 查询失败冷却区间：1 秒起步、指数退避、封顶 30 秒
     */
    private static final Duration COOLDOWN_INITIAL = Duration.ofSeconds(1);
    private static final Duration COOLDOWN_MAX = Duration.ofSeconds(30);

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
     * MAX(update_time) 刷新缓存（watch 时创建，destroy 时关闭）
     */
    private volatile RefreshableValue<Long> maxUpdateTime;

    /**
     * 自有定时任务线程池（RefreshableValue 的 scheduler 归本类所有，close 不会关闭它）
     */
    private volatile ScheduledExecutorService scheduler;

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
        stop();

        scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "db-config-watcher");
            t.setDaemon(true);
            return t;
        });

        maxUpdateTime = RefreshableValue.<Long>builder()
                .name("db-config-watcher")
                .loader(ctx -> queryMaxTimestamp())
                .refreshEvery(Duration.ofSeconds(intervalSeconds))
                .background()
                .cooldown(COOLDOWN_INITIAL, COOLDOWN_MAX)
                .scheduler(scheduler)
                .onChange((oldV, newV) -> {
                    // 首载 oldValue 为 null，仅建立基线不触发；此后仅严格增大才触发
                    if (oldV != null && newV > oldV) {
                        log.debug("[DbConfigWatcher] Database config changed ({} -> {})", oldV, newV);
                        changeSignal.run();
                    }
                })
                .build();

        // 同步建立基线：失败不阻断 watch，由 recoverBaseline 周期任务补建
        refreshBaseline();

        // 基线建立失败时的后台补建（RefreshableValue 的后台 tick 仅在已有值时刷新）
        scheduler.scheduleWithFixedDelay(
                this::refreshBaseline,
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
        stop();
    }

    /**
     * 尝试建立基线：已有值时直接返回，查询失败仅记日志。
     */
    private void refreshBaseline() {
        RefreshableValue<Long> value = maxUpdateTime;
        if (value == null || value.peek() != null) {
            return;
        }
        try {
            value.refresh();
        } catch (RuntimeException e) {
            log.warn("[DbConfigWatcher] Failed to establish baseline, will retry", e);
        }
    }

    /**
     * 获取表内最大更新时间。
     *
     * @return 毫秒级时间戳，空表或 NULL 返回 0
     * @throws SQLException 查询失败
     */
    private long queryMaxTimestamp() throws SQLException {
        String sql = "SELECT MAX(" + options.getUpdateTimeColumn() + ") AS max_time FROM " + options.getTableName();
        List<Map<String, Object>> rows = JdbcUtil.query(dataSource, sql);
        if (rows.isEmpty()) {
            return 0L;
        }

        Object maxTime = rows.get(0).get("max_time");
        if (maxTime == null) {
            return 0L;
        }

        // 映射数据库时间类型为毫秒级时间戳
        if (maxTime instanceof Timestamp) {
            return ((Timestamp) maxTime).getTime();
        }

        return Long.parseLong(maxTime.toString());
    }

    public long getFailureCount() {
        RefreshableValue<Long> value = maxUpdateTime;
        return value == null ? 0L : value.status().getFailureCount();
    }

    public String getLastErrorMessage() {
        RefreshableValue<Long> value = maxUpdateTime;
        if (value == null) {
            return null;
        }
        Throwable lastError = value.status().getLastError();
        return lastError == null ? null : lastError.getMessage();
    }

    /**
     * 关闭当前刷新缓存并停掉自有调度器（支持重复 watch 重建）。
     */
    private void stop() {
        RefreshableValue<Long> value = maxUpdateTime;
        if (value != null) {
            value.close();
            maxUpdateTime = null;
        }

        ScheduledExecutorService current = scheduler;
        if (current != null) {
            scheduler = null;
            current.shutdownNow();
            log.info("[DbConfigWatcher] Stopped");
        }
    }
}
