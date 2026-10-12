package com.susukkang.fgc.transaction.performance.baseline;

import com.susukkang.fgc.cap.dto.CapExceptionInsertDTO;
import com.susukkang.fgc.cap.dto.CapExceptionResolveCommand;
import com.susukkang.fgc.transaction.performance.baseline.BaselineCapExceptionStatusRow;
import com.susukkang.fgc.common.persistence.baseline.JdbcBaseline;
import javax.sql.DataSource;
import java.util.HashMap;

/** 전환 전 SQL을 실행하는 테스트 전용 JDBC 기준 구현. */
public class PaymentCapExceptionJdbcBaseline {
    private final JdbcBaseline jdbc;
    public PaymentCapExceptionJdbcBaseline(DataSource dataSource) { this.jdbc = new JdbcBaseline(dataSource); }

    public int insertException(CapExceptionInsertDTO capExceptionInsertDTO) {
        return jdbc.update("com.susukkang.fgc.transaction.performance.baseline.PaymentCapExceptionJdbcBaseline.insertException", capExceptionInsertDTO);
    }
    public BaselineCapExceptionStatusRow selectExceptionForUpdate(Long exceptionCaseId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("exceptionCaseId", exceptionCaseId);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.PaymentCapExceptionJdbcBaseline.selectExceptionForUpdate", parameters);
    }
    public int insertExceptionAction(CapExceptionResolveCommand command) {
        return jdbc.update("com.susukkang.fgc.transaction.performance.baseline.PaymentCapExceptionJdbcBaseline.insertExceptionAction", command);
    }
    public int updateExceptionResolved(Long exceptionCaseId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("exceptionCaseId", exceptionCaseId);
        return jdbc.update("com.susukkang.fgc.transaction.performance.baseline.PaymentCapExceptionJdbcBaseline.updateExceptionResolved", parameters);
    }
    public boolean existsUnresolvedViolation(Long paymentId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("paymentId", paymentId);
        return jdbc.selectOne("com.susukkang.fgc.transaction.performance.baseline.PaymentCapExceptionJdbcBaseline.existsUnresolvedViolation", parameters);
    }
}
