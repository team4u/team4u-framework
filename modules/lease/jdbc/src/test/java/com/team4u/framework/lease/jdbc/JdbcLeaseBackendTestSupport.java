package com.team4u.framework.lease.jdbc;

import com.team4u.framework.base.jdbc.JdbcUtil;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * Minimal H2 MySQL-mode datasource and schema initializer for JDBC contract tests.
 */
final class JdbcLeaseBackendTestSupport {

    private static final String SCHEMA_RESOURCE = "lease_task_h2.sql";

    private JdbcLeaseBackendTestSupport() {
    }

    static DataSource newDataSource() {
        String dbName = "lease_" + System.nanoTime();
        String jdbcUrl = "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        DataSource dataSource = new SimpleDataSource(jdbcUrl, "sa", "");
        try {
            JdbcUtil.execute(dataSource, "DROP TABLE IF EXISTS lease_task");
            initializeSchema(dataSource);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to initialize H2 schema", e);
        }
        return dataSource;
    }

    private static void initializeSchema(DataSource dataSource) throws SQLException {
        String schemaSql = loadSchemaSql();
        for (String statement : schemaSql.split(";")) {
            String sql = statement.trim();
            if (!sql.isEmpty()) {
                JdbcUtil.execute(dataSource, sql);
            }
        }
    }

    private static String loadSchemaSql() {
        try (InputStream inputStream = JdbcLeaseBackendTestSupport.class.getClassLoader()
                .getResourceAsStream(SCHEMA_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("schema resource not found: " + SCHEMA_RESOURCE);
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load schema resource: " + SCHEMA_RESOURCE, e);
        }
    }

    private static class SimpleDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;

        SimpleDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return false;
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return 0;
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return null;
        }
    }
}
