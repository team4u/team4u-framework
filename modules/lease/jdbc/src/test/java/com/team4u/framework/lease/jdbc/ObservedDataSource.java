package com.team4u.framework.lease.jdbc;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDBC observer used by module tests to assert SQL shape, statement count and bound parameters.
 */
final class ObservedDataSource {

    private final DataSource dataSource;
    private final AtomicInteger executionCount = new AtomicInteger();
    private final List<SqlInterceptor> interceptors = new CopyOnWriteArrayList<SqlInterceptor>();
    private final List<String> executedSql = new CopyOnWriteArrayList<String>();
    private final List<List<Object>> executedParameters = new CopyOnWriteArrayList<List<Object>>();
    private String currentSql;
    private final TreeMap<Integer, Object> currentParameters = new TreeMap<Integer, Object>();

    private ObservedDataSource(DataSource delegate) {
        this.dataSource = proxyDataSource(delegate);
    }

    static ObservedDataSource wrap(DataSource delegate) {
        return new ObservedDataSource(delegate);
    }

    DataSource dataSource() {
        return dataSource;
    }

    int executionCount() {
        return executionCount.get();
    }

    void resetCount() {
        executionCount.set(0);
        executedSql.clear();
        executedParameters.clear();
    }

    List<String> executedSql() {
        return new ArrayList<String>(executedSql);
    }

    List<List<Object>> executedParameters() {
        return new ArrayList<List<Object>>(executedParameters);
    }

    void addInterceptor(SqlInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    private DataSource proxyDataSource(final DataSource delegate) {
        return proxy(DataSource.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                try {
                    Object result = method.invoke(delegate, args);
                    if (result instanceof java.sql.Connection) {
                        return proxyConnection((java.sql.Connection) result);
                    }
                    return result;
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        });
    }

    private java.sql.Connection proxyConnection(final java.sql.Connection delegate) {
        return proxy(java.sql.Connection.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                try {
                    Object result = method.invoke(delegate, args);
                    String name = method.getName();
                    if ((result instanceof PreparedStatement)
                            && ("prepareStatement".equals(name) || "prepareCall".equals(name))
                            && args != null && args.length > 0 && args[0] instanceof String) {
                        return proxyPreparedStatement((PreparedStatement) result, (String) args[0]);
                    }
                    return result;
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        });
    }

    private PreparedStatement proxyPreparedStatement(final PreparedStatement delegate, final String sql) {
        return proxy(PreparedStatement.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                try {
                    if (name.startsWith("set") && args != null && args.length >= 2
                            && args[0] instanceof Integer) {
                        currentParameters.put((Integer) args[0], args[1]);
                    }
                    if ("execute".equals(name) || "executeUpdate".equals(name)
                            || "executeQuery".equals(name) || "executeLargeUpdate".equals(name)) {
                        beforeExecute(sql);
                    }
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                } finally {
                    if ("execute".equals(name) || "executeUpdate".equals(name)
                            || "executeQuery".equals(name) || "executeLargeUpdate".equals(name)) {
                        currentParameters.clear();
                    }
                }
            }
        });
    }

    private void beforeExecute(String sql) throws SQLException {
        executionCount.incrementAndGet();
        String normalizedSql = normalize(sql);
        executedSql.add(normalizedSql);
        executedParameters.add(new ArrayList<Object>(currentParameters.values()));
        for (SqlInterceptor interceptor : interceptors) {
            interceptor.beforeExecute(normalizedSql);
        }
    }

    private String normalize(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    interface SqlInterceptor {
        void beforeExecute(String normalizedSql) throws SQLException;
    }
}
