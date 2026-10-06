package com.susukkang.fgc.arbitrage.service;

import com.susukkang.fgc.arbitrage.dto.ReArbitrageCheckRequest;
import com.susukkang.fgc.arbitrage.repository.ArbitrageExceptionRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 커밋 경계는 테스트 전용 DB에서 검증한다. 개발 DB나 공유 테스트 DB에 이력을 남기지 않는다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_arbitrage_boundary?currentSchema=fgc")
class ArbitrageTransactionBoundaryIntegrationTest {
    @Autowired ArbitrageCheckBatchItemService itemService;
    @Autowired ArbitrageService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean ArbitrageExceptionRepository exceptionRepository;

    @Test
    void rollsBackPartialDetectionRetriesWithoutDuplicatesAndCommitsOtherContractIndependently() {
        List<Reference> references = jdbc.query("SELECT contract_id, contract_date FROM fgc.insurance_contract ORDER BY contract_id LIMIT 2",
                (rs, i) -> new Reference(rs.getLong(1), LocalDate.of(1900, 1, 1)));
        assertThat(references).hasSize(2);
        Long runId = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                VALUES (DATE '2094-01-01', 1, 'PRE_CONFIRM', 'CREATED') RETURNING validation_run_id
                """, Long.class);
        Reference first = references.getFirst();
        ArbitrageExceptionRepository spyTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(exceptionRepository);
        doAnswer(invocation -> {
            invocation.callRealMethod(); // 계산 결과와 예외·occurrence까지 쓴 다음 실패시킨다.
            throw new FgcBusinessException(FgcErrorCode.COMMON_500, Map.of());
        }).when(spyTarget).insertArbitrageReviewCase(anyString(), eq(runId), eq(first.id()), anyLong(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> itemService.process(runId, first.id(), first.date())).isInstanceOf(FgcBusinessException.class);
        assertThat(count("arbitrage_check", runId)).isZero();
        assertThat(count("exception_occurrence", runId)).isZero();
        assertThat(count("exception_case", runId)).isZero();
        reset(spyTarget);

        Reference second = references.get(1);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            itemService.process(runId, second.id(), second.date());
            status.setRollbackOnly(); // 바깥 청크가 롤백돼도 REQUIRES_NEW 성공 계약은 남는다.
        });
        assertThat(count("arbitrage_check", runId)).isEqualTo(1);
        var retried = itemService.process(runId, first.id(), first.date());
        var repeated = itemService.process(runId, first.id(), first.date());
        assertThat(repeated.getArbitrageCheckId()).isEqualTo(retried.getArbitrageCheckId());
        assertThat(count("arbitrage_check", runId)).isEqualTo(2);
        assertThat(count("exception_occurrence", runId)).isEqualTo(2);
        assertThat(count("exception_case", runId)).isEqualTo(2);
    }

    @Test
    void manualCheckCreatesPolicySnapshotCompletesRunAndRecordsAudit() {
        Reference reference = jdbc.queryForObject("SELECT contract_id, contract_date FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1",
                (rs, i) -> new Reference(rs.getLong(1), LocalDate.of(1900, 1, 1)));
        Long userId = jdbc.queryForObject("SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
        var result = service.reArbitrageCheck(reference.id(), new ReArbitrageCheckRequest(reference.date(), "JPA 수동 검증 연계"), userId);
        var run = jdbc.queryForMap("SELECT run_type, status, current_step, policy_snapshot FROM fgc.validation_run WHERE validation_run_id = ?", result.getValidationRunId());
        assertThat(run).containsEntry("run_type", "MANUAL_CONTRACT").containsEntry("status", "COMPLETED").containsEntry("current_step", 8);
        assertThat(run.get("policy_snapshot").toString()).contains("capRuleSets", "refundRateTables", "productOfferings");
        assertThat(result.getArbitrageCheckId()).isNotNull();
        assertThat(count("arbitrage_check", result.getValidationRunId())).isEqualTo(1);
        assertThat(count("exception_occurrence", result.getValidationRunId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fgc.audit_log WHERE action_code = 'ARBITRAGE_RECHECKED' AND entity_id = ?", Long.class,
                result.getArbitrageCheckId().toString())).isEqualTo(1);
    }

    private long count(String table, Long runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fgc." + table + " WHERE validation_run_id = ?", Long.class, runId);
    }
    private record Reference(Long id, LocalDate date) { }
}
