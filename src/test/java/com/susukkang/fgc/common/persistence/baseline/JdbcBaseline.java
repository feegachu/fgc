package com.susukkang.fgc.common.persistence.baseline;

import org.springframework.beans.BeanWrapperImpl;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import com.susukkang.fgc.schedule.dto.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 전환 전 SQL을 같은 Spring 트랜잭션에서 실행하는 테스트 전용 JDBC 어댑터.
 * 조회마다 DB를 읽으며 운영 Repository 코드·SQL을 재사용하지 않는다.
 * 프레임워크 성능 비교가 아닌 결과·SQL 수·실행 계획의 회귀 기준이다.
 */
public final class JdbcBaseline {
    private final NamedParameterJdbcTemplate jdbc;
    private final AtomicInteger statements;

    public JdbcBaseline(DataSource dataSource) { this(dataSource, new AtomicInteger()); }
    public JdbcBaseline(DataSource dataSource, AtomicInteger statements) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.statements = statements;
    }

    public <T> List<T> selectList(String name) { return selectList(name, Map.of()); }
    @SuppressWarnings("unchecked")
    public <T> List<T> selectList(String name, Object input) {
        Statement statement = BaselineStatements.lookup(name, input);
        statements.incrementAndGet();
        return (List<T>) jdbc.query(statement.sql(), statement.parameters(), statement.mapper());
    }

    public <T> T selectOne(String name) { return selectOne(name, Map.of()); }
    @SuppressWarnings("unchecked")
    public <T> T selectOne(String name, Object input) {
        List<T> results = selectList(name, input);
        if (results.isEmpty()) return null;
        if (results.getFirst() instanceof ScheduleDetailResponse first) {
            var lines = results.stream().map(ScheduleDetailResponse.class::cast)
                    .flatMap(r -> r.getLines().stream()).toList();
            return (T) ScheduleDetailResponse.builder().scheduleHeaderId(first.getScheduleHeaderId())
                    .header(first.getHeader()).lines(lines).build();
        }
        return org.springframework.dao.support.DataAccessUtils.requiredSingleResult(results);
    }

    public int insert(String name, Object input) { return update(name, input); }
    @SuppressWarnings({"rawtypes", "unchecked"})
    public int update(String name, Object input) {
        Statement statement = BaselineStatements.lookup(name, input);
        statements.incrementAndGet();
        if (statement.keyProperty().isEmpty()) return jdbc.update(statement.sql(), statement.parameters());
        var keys = new GeneratedKeyHolder();
        String column = statement.keyColumn().isEmpty()
                ? statement.keyProperty().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT)
                : statement.keyColumn();
        int count = jdbc.update(statement.sql(), statement.parameters(), keys, new String[]{column});
        Object key = count == 0 ? null : Objects.requireNonNull(keys.getKey()).longValue();
        if (input instanceof Map map) map.put(statement.keyProperty(), key);
        else new BeanWrapperImpl(input).setPropertyValue(statement.keyProperty(), key);
        return count;
    }

    record Statement(String sql, MapSqlParameterSource parameters, RowMapper<?> mapper,
                     String keyProperty, String keyColumn) { }

    static Object value(Object bean, String path) {
        Object current = bean;
        for (String part : path.split("\\.")) {
            if (current == null) return null;
            if (current instanceof Map<?, ?> map) { current = map.get(part); continue; }
            if (current.getClass().isRecord()) {
                try { current = current.getClass().getMethod(part).invoke(current); }
                catch (ReflectiveOperationException e) { throw new IllegalArgumentException(path, e); }
            } else current = new BeanWrapperImpl(current).getPropertyValue(part);
        }
        return current;
    }
    static Object parameter(Object value) { return value instanceof Enum<?> e ? e.name() : value; }
    static <T> RowMapper<T> scalar(Class<T> type) { return SingleColumnRowMapper.newInstance(type); }
    static RowMapper<Map<String,Object>> mapRows() { return new ColumnMapRowMapper(); }
    @SuppressWarnings({"rawtypes", "unchecked"})
    static <T> RowMapper<T> rows(Class<T> type) {
        if (type.isRecord()) return DataClassRowMapper.newInstance(type);
        return (rs, n) -> {
            T row = org.springframework.beans.BeanUtils.instantiateClass(type);
            Map<String, Integer> columns = new HashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                columns.put(rs.getMetaData().getColumnLabel(i).replace("_", "").toLowerCase(Locale.ROOT), i);
            for (var field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                Integer index = columns.get(field.getName().toLowerCase(Locale.ROOT));
                if (index == null) continue;
                Class<?> target = org.springframework.util.ClassUtils.resolvePrimitiveIfNecessary(field.getType());
                Object value = target.isEnum() ? rs.getString(index)
                        : org.springframework.jdbc.support.JdbcUtils.getResultSetValue(rs, index, target);
                if (value != null && target.isEnum()) value = Enum.valueOf((Class) target, (String) value);
                if (value != null || !field.getType().isPrimitive()) {
                    org.springframework.util.ReflectionUtils.makeAccessible(field);
                    org.springframework.util.ReflectionUtils.setField(field, row, value);
                }
            }
            return row;
        };
    }
    static RowMapper<ScheduleDetailResponse> scheduleRows() {
        return (rs, n) -> {
            var header = rows(ScheduleHeaderResponse.class).mapRow(rs,n);
            org.springframework.test.util.ReflectionTestUtils.setField(header, "status",
                    com.susukkang.fgc.common.code.ScheduleHeaderStatus.valueOf(rs.getString("header_status")));
            var line = rows(ScheduleLineResponse.class).mapRow(rs,n);
            org.springframework.test.util.ReflectionTestUtils.setField(line, "ruleRef",
                    rs.getObject("source_commission_rule_id", Long.class));
            return ScheduleDetailResponse.builder().scheduleHeaderId(header.getScheduleHeaderId()).header(header)
                    .lines(rs.getObject("line_no") == null ? List.of() : List.of(line)).build();
        };
    }
}
