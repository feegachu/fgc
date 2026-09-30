package com.susukkang.fgc.transaction.performance;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 설명 : 지급 성능 검증의 현재 스레드 JDBC 호출 시간과 원문 SQL을 관측한다.
 * 호출 시간에는 리플렉션 전달 비용이 포함되며 DB 서버 실행 시간으로 해석하지 않는다.
 * ResultSet 시간은 JDBC 결과 접근 시간이며, execute 중 미리 수신한 결과는 execute에 포함된다.
 * 스트림의 후속 I/O와 다른 스레드의 JDBC 호출은 계측하지 않는다.
 *
 * @author Codex
 * @since 2026-09-30
 * @version 1.0
 */
public final class PaymentJdbcObservation {
    private static final ThreadLocal<Observation> CURRENT = new ThreadLocal<>();

    private PaymentJdbcObservation() { }

    public static void start() {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("A JDBC observation is already active on this thread");
        }
        CURRENT.set(new Observation());
    }

    public static Snapshot stop() {
        Observation observation = CURRENT.get();
        CURRENT.remove();
        if (observation == null) {
            throw new IllegalStateException("No JDBC observation is active on this thread");
        }
        return new Snapshot(observation.prepareNanos, observation.executeNanos,
                observation.resultSetNanos, observation.otherJdbcNanos, observation.executions);
    }

    public record Snapshot(long prepareNanos, long executeNanos, long resultSetNanos,
                           long otherJdbcNanos, List<SqlExecution> executions) {
        public Snapshot {
            executions = List.copyOf(executions);
        }

        public long jdbcNanos() {
            return prepareNanos + executeNanos + resultSetNanos + otherJdbcNanos;
        }
    }

    /** 배치는 JDBC 실행 호출 1회이며 실제 SQL문 또는 영향을 받은 행의 수와 같지 않다. */
    public record SqlExecution(String sql, long nanos, String method) {
        public boolean batchExecution() {
            return "executeBatch".equals(method) || "executeLargeBatch".equals(method);
        }
    }

    private enum Category { PREPARE, EXECUTE, RESULT_SET, OTHER }

    private static final class Observation {
        private long prepareNanos;
        private long executeNanos;
        private long resultSetNanos;
        private long otherJdbcNanos;
        private int invocationDepth;
        private final List<SqlExecution> executions = new ArrayList<>();

        private void add(Category category, long nanos, String sql, String method) {
            switch (category) {
                case PREPARE -> prepareNanos += nanos;
                case EXECUTE -> {
                    executeNanos += nanos;
                    executions.add(new SqlExecution(sql, nanos, method));
                }
                case RESULT_SET -> resultSetNanos += nanos;
                case OTHER -> otherJdbcNanos += nanos;
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        public static BeanPostProcessor paymentJdbcObservationDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof DataSource source && !(bean instanceof ObservedDataSource)) {
                        return new ObservedDataSource(source);
                    }
                    return bean;
                }
            };
        }
    }

    private static final class ObservedDataSource extends DelegatingDataSource {
        private ObservedDataSource(DataSource delegate) {
            super(delegate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return observeAcquisition(() -> super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return observeAcquisition(() -> super.getConnection(username, password));
        }

        private Connection observeAcquisition(ConnectionSupplier supplier) throws SQLException {
            Observation observation = CURRENT.get();
            boolean outermost = observation != null && observation.invocationDepth++ == 0;
            long started = outermost ? System.nanoTime() : 0;
            Connection connection;
            try {
                connection = supplier.get();
            } finally {
                if (observation != null) {
                    if (outermost) {
                        observation.add(Category.OTHER, System.nanoTime() - started, null, "getConnection");
                    }
                    observation.invocationDepth--;
                }
            }
            return (Connection) new Wrappers().wrap(connection, null);
        }
    }

    @FunctionalInterface
    private interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    /** 연결 단위 식별성을 유지하여 getStatement/getConnection/생성키 결과도 같은 프록시로 읽는다. */
    private static final class Wrappers {
        private final Map<Object, Object> proxies = new IdentityHashMap<>();
        private final Map<Object, Object> delegates = new IdentityHashMap<>();

        private Object wrap(Object delegate, String statementSql) {
            Class<?> jdbcInterface = jdbcInterface(delegate);
            if (jdbcInterface == null) return delegate;
            Object existing = proxies.get(delegate);
            if (existing != null) return existing;
            List<String> batchSql = new ArrayList<>();
            Object proxy = Proxy.newProxyInstance(jdbcInterface.getClassLoader(), new Class<?>[]{jdbcInterface},
                    (self, method, arguments) -> invoke(self, delegate, method, arguments, statementSql, batchSql));
            proxies.put(delegate, proxy);
            delegates.put(proxy, delegate);
            return proxy;
        }

        private Object invoke(Object self, Object delegate, Method method, Object[] arguments,
                              String statementSql, List<String> batchSql) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "equals" -> self == arguments[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "toString" -> invokeDelegate(delegate, method, arguments);
                    default -> invokeDelegate(delegate, method, arguments);
                };
            }
            // JDBC 표준 인터페이스 unwrap은 계측을 보존하며 vendor 인터페이스는 드라이버에 위임한다.
            if (("unwrap".equals(name) || "isWrapperFor".equals(name))
                    && arguments != null && arguments.length == 1
                    && arguments[0] instanceof Class<?> requested && requested.isInstance(self)) {
                return "unwrap".equals(name) ? self : true;
            }

            Category category = category(delegate, name);
            String executedSql = category == Category.EXECUTE
                    ? executionSql(arguments, statementSql, batchSql) : null;
            Object[] actualArguments = unwrapArguments(arguments);
            Observation observation = CURRENT.get();
            boolean outermost = observation != null && observation.invocationDepth++ == 0;
            long started = outermost ? System.nanoTime() : 0;
            Object result;
            try {
                result = invokeDelegate(delegate, method, actualArguments);
            } finally {
                if (observation != null) {
                    if (outermost) {
                        observation.add(category, System.nanoTime() - started, executedSql, name);
                    }
                    observation.invocationDepth--;
                }
            }

            if (delegate instanceof Statement) {
                if ("addBatch".equals(name) && arguments != null && arguments.length > 0
                        && arguments[0] instanceof String sql) {
                    batchSql.add(sql);
                } else if ("clearBatch".equals(name) || "executeBatch".equals(name)
                        || "executeLargeBatch".equals(name)) {
                    batchSql.clear();
                }
            }
            if ("unwrap".equals(name)) return result;
            // 준비·결과 접근 타이머가 끝난 후 래퍼를 구성하여 중첩 시간을 중복 합산하지 않는다.
            String preparedSql = category == Category.PREPARE && arguments != null && arguments.length > 0
                    && arguments[0] instanceof String sql ? sql : null;
            return wrap(result, preparedSql);
        }

        private Object[] unwrapArguments(Object[] arguments) {
            // setArray/setBlob 등은 같은 드라이버가 생성한 실제 객체를 받을 수 있도록 돌려준다.
            Object[] actualArguments = arguments;
            if (arguments != null) {
                for (int index = 0; index < arguments.length; index++) {
                    Object original = delegates.get(arguments[index]);
                    if (original != null) {
                        if (actualArguments == arguments) actualArguments = arguments.clone();
                        actualArguments[index] = original;
                    }
                }
            }
            return actualArguments;
        }

        private static Object invokeDelegate(Object delegate, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(delegate, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        private static String executionSql(Object[] arguments, String statementSql, List<String> batchSql) {
            if (arguments != null && arguments.length > 0 && arguments[0] instanceof String sql) return sql;
            if (statementSql != null) return statementSql;
            if (!batchSql.isEmpty()) return String.join("\n", batchSql);
            return "<unidentified SQL>";
        }

        private static Category category(Object delegate, String name) {
            if (delegate instanceof Connection && ("prepareStatement".equals(name)
                    || "prepareCall".equals(name) || "createStatement".equals(name))) return Category.PREPARE;
            if (delegate instanceof Statement && ("execute".equals(name) || "executeQuery".equals(name)
                    || "executeUpdate".equals(name) || "executeLargeUpdate".equals(name)
                    || "executeBatch".equals(name) || "executeLargeBatch".equals(name))) return Category.EXECUTE;
            if (delegate instanceof ResultSet) return Category.RESULT_SET;
            return Category.OTHER;
        }

        private static Class<?> jdbcInterface(Object value) {
            if (value instanceof Connection) return Connection.class;
            if (value instanceof CallableStatement) return CallableStatement.class;
            if (value instanceof PreparedStatement) return PreparedStatement.class;
            if (value instanceof Statement) return Statement.class;
            if (value instanceof ResultSet) return ResultSet.class;
            if (value instanceof DatabaseMetaData) return DatabaseMetaData.class;
            if (value instanceof ResultSetMetaData) return ResultSetMetaData.class;
            if (value instanceof ParameterMetaData) return ParameterMetaData.class;
            if (value instanceof java.sql.Array) return java.sql.Array.class;
            if (value instanceof NClob) return NClob.class;
            if (value instanceof Clob) return Clob.class;
            if (value instanceof Blob) return Blob.class;
            if (value instanceof SQLXML) return SQLXML.class;
            if (value instanceof Ref) return Ref.class;
            if (value instanceof Struct) return Struct.class;
            if (value instanceof Savepoint) return Savepoint.class;
            return null;
        }
    }
}
