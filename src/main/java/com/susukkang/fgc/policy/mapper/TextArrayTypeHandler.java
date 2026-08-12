package com.susukkang.fgc.policy.mapper;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * PostgreSQL text[] 컬럼(policy_version.regulation_refs / source_refs)을
 * List&lt;String&gt; 으로 읽는 읽기 전용 TypeHandler.
 * 정책 쓰기는 1차 범위가 아니므로(POL-W02, 2차) 파라미터 바인딩은 지원하지 않는다.
 * resultMap 의 typeHandler 속성으로만 등록해 전역 설정을 건드리지 않는다.
 */
public class TextArrayTypeHandler extends BaseTypeHandler<List<String>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType) {
        throw new UnsupportedOperationException("text[] 쓰기는 1차 범위가 아니다 (정책 편집은 2차 POL-W02)");
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    private List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }
}
