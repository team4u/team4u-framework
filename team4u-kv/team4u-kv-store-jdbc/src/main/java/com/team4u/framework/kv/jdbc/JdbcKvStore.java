package com.team4u.framework.kv.jdbc;

import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.CounterCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.ScanCapable;
import com.team4u.framework.kv.SpaceKey;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JDBC 键值存储
 * <p>
 * 基于原生 JDBC（仅依赖 {@link DataSource}），适用于 MySQL、H2 等关系库。
 * 过期采用查询条件过滤 + 惰性清理（{@code expire_at} 为 epoch 毫秒，0 为永不过期）。
 * </p>
 * 原子性实现：
 * <ul>
 *     <li>IF_ABSENT：{@code (space, name)} 唯一索引；写入前先删除同键已过期记录，
 *     避免过期数据阻塞写入</li>
 *     <li>CAS：条件 UPDATE / DELETE（值精确匹配 + 未过期），
 *     数据库行锁保证原子性，是 {@code team4u-kv-lock} 的可靠底座</li>
 *     <li>计数：独立计数表 {@code kv_counter} 上 {@code SELECT FOR UPDATE} 行锁
 *     串行化「读-改-写」（实现 {@link CounterCapable}），计数与值域互不干扰；
 *     计数 TTL（{@code expire_at} 列，0 为永不过期）的过期重置与设置
 *     同样在行锁内原子完成</li>
 * </ul>
 * 默认表名 {@code kv_store} / {@code kv_counter}，可通过 {@link Config} 自定义；
 * {@code autoCreateTable=true} 时启动自动建表（H2/MySQL 语法兼容）。
 *
 * @author jay.wu
 */
@Slf4j
public class JdbcKvStore implements KvStore, CasCapable, ScanCapable, CounterCapable,
        AutoCloseable {


    /**
     * 建表 DDL：H2 与 MySQL 均兼容
     */
    public static final String DEFAULT_DDL =
            "CREATE TABLE IF NOT EXISTS kv_store ("
                    + "space VARCHAR(100) NOT NULL, "
                    + "name VARCHAR(255) NOT NULL, "
                    + "kv_value VARCHAR(4000) NOT NULL, "
                    + "expire_at BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (space, name)"
                    + ")";

    /**
     * 计数表 DDL：H2 与 MySQL 均兼容（expire_at 为计数键过期时间，0 为永不过期）
     */
    public static final String DEFAULT_COUNTER_DDL =
            "CREATE TABLE IF NOT EXISTS kv_counter ("
                    + "space VARCHAR(100) NOT NULL, "
                    + "name VARCHAR(255) NOT NULL, "
                    + "counter_value BIGINT NOT NULL DEFAULT 0, "
                    + "expire_at BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (space, name)"
                    + ")";

    private final DataSource dataSource;
    private final Config config;
    private final Clock clock;

    public JdbcKvStore(DataSource dataSource) {
        this(dataSource, new Config(), Clock.systemUTC());
    }

    public JdbcKvStore(DataSource dataSource, Config config, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = clock;
        if (config.isAutoCreateTable()) {
            executeDdl();
        }
    }

    private void executeDdl() {
        String ddl = DEFAULT_DDL
                .replace("kv_store", config.getTableName())
                .replace("VARCHAR(4000)", config.getValueColumnDefinition());
        String counterDdl = DEFAULT_COUNTER_DDL
                .replace("kv_counter", config.getCounterTableName());
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(ddl);
            st.execute(counterDdl);
        } catch (SQLException e) {
            throw new KvStoreException("Failed to create kv table: "
                    + config.getTableName() + "/" + config.getCounterTableName(), e);
        }
    }

    @Override
    public KvRecord get(SpaceKey key) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT kv_value, expire_at FROM " + config.getTableName()
                             + " WHERE space=? AND name=?")) {
            ps.setString(1, key.getSpace());
            ps.setString(2, key.getKey());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                KvRecord record = KvRecord.ofRaw(rs.getString(1), rs.getLong(2));
                long now = now();
                if (record.isExpired(now)) {
                    // 惰性清理：读到的过期行顺手删除（仅删除当时已确认过期的行，
                    // 防止误删并发新写入——谓词使用读取时刻的时间）
                    deleteQuietly(key, now);
                    return null;
                }
                return record;
            }
        } catch (SQLException e) {
            throw new KvStoreException("Get failed|key=" + key, e);
        }
    }

    @Override
    public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
        Objects.requireNonNull(record, "record");
        if (mode == PutMode.IF_ABSENT) {
            return putIfAbsent(key, record);
        }
        return putSet(key, record);
    }

    private boolean putSet(SpaceKey key, KvRecord record) {
        // 先 UPDATE，0 行则 INSERT；INSERT 撞唯一索引说明并发写入，重试 UPDATE
        int updated = executeUpdate(
                "UPDATE " + config.getTableName() + " SET kv_value=?, expire_at=?"
                        + " WHERE space=? AND name=?",
                record.getValue(), record.getExpireAt(), key.getSpace(), key.getKey());
        if (updated > 0) {
            return true;
        }
        try {
            return executeUpdate(
                    "INSERT INTO " + config.getTableName() + " (space, name, kv_value, expire_at)"
                            + " VALUES (?, ?, ?, ?)",
                    key.getSpace(), key.getKey(), record.getValue(), record.getExpireAt()) > 0;
        } catch (KvStoreException e) {
            if (isUniqueViolation(e.getCause())) {
                // 并发写入撞唯一索引：转为 UPDATE
                return executeUpdate(
                        "UPDATE " + config.getTableName() + " SET kv_value=?, expire_at=?"
                                + " WHERE space=? AND name=?",
                        record.getValue(), record.getExpireAt(), key.getSpace(), key.getKey()) > 0;
            }
            throw e;
        }
    }

    /**
     * 仅唯一约束冲突（SQLState 23*）视为并发写入，其余（连接故障等）原样抛出，
     * 防止基础设施故障被误判为「键已存在」
     */
    private static boolean isUniqueViolation(Throwable t) {
        if (!(t instanceof SQLException)) {
            return false;
        }
        SQLException e = (SQLException) t;
        String sqlState = e.getSQLState();
        return e instanceof java.sql.SQLIntegrityConstraintViolationException
                || (sqlState != null && sqlState.startsWith("23"));
    }

    private boolean putIfAbsent(SpaceKey key, KvRecord record) {
        // 先删除同键已过期记录，避免过期数据阻塞 SETNX
        executeUpdate("DELETE FROM " + config.getTableName()
                        + " WHERE space=? AND name=? AND expire_at > 0 AND expire_at <= ?",
                key.getSpace(), key.getKey(), now());
        try {
            return executeUpdate(
                    "INSERT INTO " + config.getTableName() + " (space, name, kv_value, expire_at)"
                            + " VALUES (?, ?, ?, ?)",
                    key.getSpace(), key.getKey(), record.getValue(), record.getExpireAt()) > 0;
        } catch (KvStoreException e) {
            if (isUniqueViolation(e.getCause())) {
                // 唯一索引冲突 = 键已存在
                return false;
            }
            throw e;
        }
    }

    @Override
    public boolean remove(SpaceKey key) {
        boolean removed = executeUpdate(
                "DELETE FROM " + config.getTableName()
                        + " WHERE space=? AND name=? AND (expire_at = 0 OR expire_at > ?)",
                key.getSpace(), key.getKey(), now()) > 0;
        if (!removed) {
            // 顺手清理可能存在的过期残留行（仅存活过期行）
            deleteQuietly(key, now());
        }
        return removed;
    }

    @Override
    public boolean expire(SpaceKey key, long ttlMillis) {
        long expireAt = KvRecord.expireAtOf(ttlMillis, now());
        return executeUpdate(
                "UPDATE " + config.getTableName() + " SET expire_at=?"
                        + " WHERE space=? AND name=? AND (expire_at = 0 OR expire_at > ?)",
                expireAt, key.getSpace(), key.getKey(), now()) > 0;
    }

    @Override
    public boolean compareAndSet(SpaceKey key, String expectedValue, KvRecord update) {
        return executeUpdate(
                "UPDATE " + config.getTableName() + " SET kv_value=?, expire_at=?"
                        + " WHERE space=? AND name=? AND kv_value=? AND (expire_at = 0 OR expire_at > ?)",
                update.getValue(), update.getExpireAt(),
                key.getSpace(), key.getKey(), expectedValue, now()) > 0;
    }

    @Override
    public boolean compareAndRemove(SpaceKey key, String expectedValue) {
        return executeUpdate(
                "DELETE FROM " + config.getTableName()
                        + " WHERE space=? AND name=? AND kv_value=? AND (expire_at = 0 OR expire_at > ?)",
                key.getSpace(), key.getKey(), expectedValue, now()) > 0;
    }

    @Override
    public List<SpaceKey> scan(String space) {
        List<SpaceKey> keys = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name FROM " + config.getTableName()
                             + " WHERE space=? AND (expire_at = 0 OR expire_at > ?)")) {
            ps.setString(1, space);
            ps.setLong(2, now());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(SpaceKey.of(space, rs.getString(1)));
                }
            }
            return keys;
        } catch (SQLException e) {
            throw new KvStoreException("Scan failed|space=" + space, e);
        }
    }

    @Override
    public int pruneExpired(String space, int maxBatch) {
        return executeUpdate("DELETE FROM " + config.getTableName()
                        + " WHERE space=? AND expire_at > 0 AND expire_at <= ? LIMIT ?",
                space, now(), maxBatch);
    }

    @Override
    public long incrementAndGet(SpaceKey key, long delta, long ttlMillis) {
        try (Connection conn = dataSource.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                long value = doIncrement(conn, key, delta, ttlMillis);
                conn.commit();
                return value;
            } catch (SQLException e) {
                rollbackQuietly(conn);
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            throw new KvStoreException("IncrementAndGet failed|key=" + key, e);
        }
    }

    /**
     * 单事务内 SELECT FOR UPDATE 行锁串行化「读-改-写」，
     * 保证并发递增不丢失且返回值为本次递增后的精确值；
     * 过期判定与 TTL 设置同样在行锁内完成（与递增原子生效，不刷新既有 TTL）
     */
    private long doIncrement(Connection conn, SpaceKey key, long delta, long ttlMillis)
            throws SQLException {
        CounterRow row = selectCounterForUpdate(conn, key);
        if (row == null) {
            long expireAt = KvRecord.expireAtOf(ttlMillis, now());
            try {
                executeUpdate(conn, "INSERT INTO " + config.getCounterTableName()
                                + " (space, name, counter_value, expire_at) VALUES (?, ?, ?, ?)",
                        key.getSpace(), key.getKey(), delta, expireAt);
                return delta;
            } catch (SQLException e) {
                if (!isUniqueViolation(e)) {
                    throw e;
                }
                // 并发首插撞唯一索引：转为更新流程重新加锁读取
                row = selectCounterForUpdate(conn, key);
            }
        }
        long now = now();
        long current = row.value;
        long expireAt = row.expireAt;
        if (expireAt > 0 && expireAt <= now) {
            // 已过期：重置计数后重新累加，等效于键消失后重建
            current = 0;
            expireAt = ttlMillis > 0 ? KvRecord.expireAtOf(ttlMillis, now) : 0;
        } else if (ttlMillis > 0 && expireAt == 0) {
            // 存量无 TTL 键首次遇到 TTL 请求：补充设置（不刷新既有 TTL）
            expireAt = KvRecord.expireAtOf(ttlMillis, now);
        }
        long next = current + delta;
        executeUpdate(conn, "UPDATE " + config.getCounterTableName()
                        + " SET counter_value=?, expire_at=? WHERE space=? AND name=?",
                next, expireAt, key.getSpace(), key.getKey());
        return next;
    }

    /**
     * 行锁读取计数行（counter_value + expire_at）；键不存在返回 {@code null}
     */
    private CounterRow selectCounterForUpdate(Connection conn, SpaceKey key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT counter_value, expire_at FROM " + config.getCounterTableName()
                        + " WHERE space=? AND name=? FOR UPDATE")) {
            ps.setString(1, key.getSpace());
            ps.setString(2, key.getKey());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new CounterRow(rs.getLong(1), rs.getLong(2)) : null;
            }
        }
    }

    /**
     * 计数行快照（SELECT FOR UPDATE 读取结果）
     */
    private static final class CounterRow {

        private final long value;

        /**
         * 过期截止时间（epoch 毫秒），0 表示永不过期
         */
        private final long expireAt;

        private CounterRow(long value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }

    private void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            log.warn("Rollback failed", e);
        }
    }

    @Override
    public void close() {
        // DataSource 生命周期由调用方管理，此处无资源可释放
    }

    private int executeUpdate(String sql, Object... args) {
        try (Connection conn = dataSource.getConnection()) {
            return executeUpdate(conn, sql, args);
        } catch (SQLException e) {
            throw new KvStoreException("Update failed|sql=" + sql, e);
        }
    }

    private int executeUpdate(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    private void deleteQuietly(SpaceKey key, long expiredBefore) {
        try {
            executeUpdate("DELETE FROM " + config.getTableName()
                            + " WHERE space=? AND name=? AND expire_at > 0 AND expire_at <= ?",
                    key.getSpace(), key.getKey(), expiredBefore);
        } catch (RuntimeException e) {
            log.warn("Lazy delete expired row failed|key={}", key, e);
        }
    }

    private long now() {
        return clock.millis();
    }

    /**
     * JDBC 存储配置
     *
     * @author jay.wu
     */
    @lombok.Data
    @lombok.experimental.Accessors(chain = true)
    public static class Config {

        /**
         * 默认表名
         */
        public static final String DEFAULT_TABLE_NAME = "kv_store";

        /**
         * 键值表名
         */
        private String tableName = DEFAULT_TABLE_NAME;

        /**
         * 默认计数表名
         */
        public static final String DEFAULT_COUNTER_TABLE_NAME = "kv_counter";

        /**
         * 计数表名（CounterCapable 使用，与键值表相互独立）
         */
        private String counterTableName = DEFAULT_COUNTER_TABLE_NAME;

        /**
         * 启动时是否自动建表（不存在则创建）
         */
        private boolean autoCreateTable = true;

        /**
         * 值列的 DDL 类型定义。默认 VARCHAR(4000)，
         * JSON 等长值场景可调整为 TEXT 等大字段类型
         */
        private String valueColumnDefinition = "VARCHAR(4000)";
    }
}
