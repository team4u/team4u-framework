package com.team4u.framework.kv.jdbc;

import com.team4u.framework.kv.CasCapable;
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
 * </ul>
 * 默认表名 {@code kv_store}，可通过 {@link Config} 自定义；
 * {@code autoCreateTable=true} 时启动自动建表（H2/MySQL 语法兼容）。
 *
 * @author jay.wu
 */
@Slf4j
public class JdbcKvStore implements KvStore, CasCapable, ScanCapable, AutoCloseable {


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
        String ddl = DEFAULT_DDL.replace("kv_store", config.getTableName());
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute(ddl);
        } catch (SQLException e) {
            throw new KvStoreException("Failed to create kv table: " + config.getTableName(), e);
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
                if (record.isExpired(now())) {
                    // 惰性清理：读到的过期行顺手删除
                    deleteQuietly(key);
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
            if (e.getCause() instanceof SQLException) {
                return executeUpdate(
                        "UPDATE " + config.getTableName() + " SET kv_value=?, expire_at=?"
                                + " WHERE space=? AND name=?",
                        record.getValue(), record.getExpireAt(), key.getSpace(), key.getKey()) > 0;
            }
            throw e;
        }
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
            // 唯一索引冲突 = 键已存在
            return false;
        }
    }

    @Override
    public boolean remove(SpaceKey key) {
        boolean removed = executeUpdate(
                "DELETE FROM " + config.getTableName()
                        + " WHERE space=? AND name=? AND (expire_at = 0 OR expire_at > ?)",
                key.getSpace(), key.getKey(), now()) > 0;
        if (!removed) {
            // 顺手清理可能存在的过期残留行
            deleteQuietly(key);
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
    public void close() {
        // DataSource 生命周期由调用方管理，此处无资源可释放
    }

    private int executeUpdate(String sql, Object... args) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new KvStoreException("Update failed|sql=" + sql, e);
        }
    }

    private void deleteQuietly(SpaceKey key) {
        try {
            executeUpdate("DELETE FROM " + config.getTableName()
                    + " WHERE space=? AND name=?", key.getSpace(), key.getKey());
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
         * 启动时是否自动建表（不存在则创建）
         */
        private boolean autoCreateTable = true;
    }
}
