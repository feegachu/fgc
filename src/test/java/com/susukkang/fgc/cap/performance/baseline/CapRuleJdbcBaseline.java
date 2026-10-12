package com.susukkang.fgc.cap.performance.baseline;

import com.susukkang.fgc.cap.dto.CapRuleItemView;
import com.susukkang.fgc.cap.dto.CapRuleSetView;
import java.time.LocalDate;
import java.util.List;
import com.susukkang.fgc.common.persistence.baseline.JdbcBaseline;
import javax.sql.DataSource;
import java.util.HashMap;

/** 전환 전 SQL을 실행하는 테스트 전용 JDBC 기준 구현. */
public class CapRuleJdbcBaseline {
    private final JdbcBaseline jdbc;
    public CapRuleJdbcBaseline(DataSource dataSource) { this.jdbc = new JdbcBaseline(dataSource); }

    public CapRuleSetView findApplicableRuleSet(String paymentStage, LocalDate contractDate, Long insurerId, String productGroupCode, String channelCode) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentStage", paymentStage);
        parameters.put("contractDate", contractDate);
        parameters.put("insurerId", insurerId);
        parameters.put("productGroupCode", productGroupCode);
        parameters.put("channelCode", channelCode);
        return jdbc.selectOne("com.susukkang.fgc.cap.performance.baseline.CapRuleJdbcBaseline.findApplicableRuleSet", parameters);
    }
    public List<CapRuleItemView> findRuleItems(Long capRuleSetId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("capRuleSetId", capRuleSetId);
        return jdbc.selectList("com.susukkang.fgc.cap.performance.baseline.CapRuleJdbcBaseline.findRuleItems", parameters);
    }
}
