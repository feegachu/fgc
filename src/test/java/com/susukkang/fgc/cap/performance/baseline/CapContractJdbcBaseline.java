package com.susukkang.fgc.cap.performance.baseline;

import com.susukkang.fgc.cap.dto.CapContractView;
import com.susukkang.fgc.common.persistence.baseline.JdbcBaseline;
import javax.sql.DataSource;
import java.util.HashMap;

/** 전환 전 SQL을 실행하는 테스트 전용 JDBC 기준 구현. */
public class CapContractJdbcBaseline {
    private final JdbcBaseline jdbc;
    public CapContractJdbcBaseline(DataSource dataSource) { this.jdbc = new JdbcBaseline(dataSource); }

    public CapContractView findById(Long contractId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("contractId", contractId);
        return jdbc.selectOne("com.susukkang.fgc.cap.performance.baseline.CapContractJdbcBaseline.findById", parameters);
    }
}
