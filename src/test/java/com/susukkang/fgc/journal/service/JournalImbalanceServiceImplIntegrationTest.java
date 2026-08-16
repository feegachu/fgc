package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.dto.JournalImbalanceItemResponse;
import com.susukkang.fgc.journal.dto.JournalImbalanceSearchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #139 GET /api/v1/journals/imbalances(IF-API-37) — vw_journal_imbalance와 journal_header를
 * 검증 실행 ID 범위로 조회한다. 실제 DB에 자기 데이터를 만들고(롤백) 불균형·범위 제한·존재
 * 검증이 제대로 동작하는지 확인한다.
 */
@SpringBootTest
@Transactional
class JournalImbalanceServiceImplIntegrationTest {

    @Autowired
    private JournalImbalanceService journalImbalanceService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long anyContractId() {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);
    }

    private Long journalAccountId(String accountCode) {
        return jdbcTemplate.queryForObject(
                "SELECT journal_account_id FROM fgc.journal_account WHERE account_code = ?",
                Long.class, accountCode);
    }

    private Long insertValidationRun(LocalDate month, int runNo) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, status)
                VALUES (?, ?, 'CREATED')
                RETURNING validation_run_id
                """, Long.class, month, runNo);
    }

    /** INSERT는 DRAFT만 허용된다(guard_journal_header_write) — 불균형 분개는 DRAFT로만 존재할 수 있다. */
    private Long insertHeader(String journalNo, String journalType, Long contractId, Long validationRunId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                     contract_id, validation_run_id)
                VALUES (?, ?, ?, 'TEST', ?, ?, ?)
                RETURNING journal_header_id
                """, Long.class, journalNo, LocalDate.of(2026, 8, 1), journalType, journalNo,
                contractId, validationRunId);
    }

    private void insertLine(Long headerId, int lineNo, String accountCode, BigDecimal debit, BigDecimal credit) {
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line
                    (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount)
                VALUES (?, ?, ?, ?, ?)
                """, headerId, lineNo, journalAccountId(accountCode), debit, credit);
    }

    @Test
    void returnsEmptyContentWhenNoImbalanceInRun() {
        Long contractId = anyContractId();
        Long validationRunId = insertValidationRun(LocalDate.of(2026, 8, 1), 101);
        Long balancedHeaderId = insertHeader("TEST-IMB-0001", "ADJUSTMENT", contractId, validationRunId);
        insertLine(balancedHeaderId, 1, "EXPECTED_RECEIVABLE", new BigDecimal("10000.00"), BigDecimal.ZERO);
        insertLine(balancedHeaderId, 2, "EXPECTED_INCOME", BigDecimal.ZERO, new BigDecimal("10000.00"));

        JournalImbalanceSearchResponse response = journalImbalanceService.findImbalances(validationRunId);

        assertThat(response.totalCount()).isZero();
        assertThat(response.content()).isEmpty();
    }

    @Test
    void detectsDebitExcessImbalance() {
        Long contractId = anyContractId();
        Long validationRunId = insertValidationRun(LocalDate.of(2026, 8, 1), 102);
        Long headerId = insertHeader("TEST-IMB-0002", "ADJUSTMENT", contractId, validationRunId);
        insertLine(headerId, 1, "EXPECTED_RECEIVABLE", new BigDecimal("15000.00"), BigDecimal.ZERO);
        insertLine(headerId, 2, "EXPECTED_INCOME", BigDecimal.ZERO, new BigDecimal("10000.00"));

        JournalImbalanceSearchResponse response = journalImbalanceService.findImbalances(validationRunId);

        assertThat(response.totalCount()).isEqualTo(1);
        JournalImbalanceItemResponse item = response.content().get(0);
        assertThat(item.journalHeaderId()).isEqualTo(headerId);
        assertThat(item.journalType().name()).isEqualTo("ADJUSTMENT");
        assertThat(item.journalTypeLabel()).isEqualTo("조정");
        assertThat(item.status().name()).isEqualTo("DRAFT");
        assertThat(item.statusLabel()).isEqualTo("작성중");
        assertThat(item.debitTotal()).isEqualByComparingTo("15000.00");
        assertThat(item.creditTotal()).isEqualByComparingTo("10000.00");
        assertThat(item.differenceAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void detectsCreditExcessImbalance() {
        Long contractId = anyContractId();
        Long validationRunId = insertValidationRun(LocalDate.of(2026, 8, 1), 103);
        Long headerId = insertHeader("TEST-IMB-0003", "ADJUSTMENT", contractId, validationRunId);
        insertLine(headerId, 1, "EXPECTED_RECEIVABLE", new BigDecimal("10000.00"), BigDecimal.ZERO);
        insertLine(headerId, 2, "EXPECTED_INCOME", BigDecimal.ZERO, new BigDecimal("18000.00"));

        JournalImbalanceSearchResponse response = journalImbalanceService.findImbalances(validationRunId);

        assertThat(response.totalCount()).isEqualTo(1);
        JournalImbalanceItemResponse item = response.content().get(0);
        assertThat(item.debitTotal()).isEqualByComparingTo("10000.00");
        assertThat(item.creditTotal()).isEqualByComparingTo("18000.00");
        assertThat(item.differenceAmount()).isEqualByComparingTo("-8000.00");
    }

    @Test
    // 다른 검증 실행의 불균형은 이 실행 결과에 섞이면 안 된다(화면정의서 1464행:
    // "2·5번은 반드시 실행 범위로 좁혀야 합니다").
    void excludesImbalancesFromOtherValidationRuns() {
        Long contractId = anyContractId();
        Long targetRunId = insertValidationRun(LocalDate.of(2026, 8, 1), 104);
        Long otherRunId = insertValidationRun(LocalDate.of(2026, 9, 1), 1);

        Long targetHeaderId = insertHeader("TEST-IMB-0004", "ADJUSTMENT", contractId, targetRunId);
        insertLine(targetHeaderId, 1, "EXPECTED_RECEIVABLE", new BigDecimal("5000.00"), BigDecimal.ZERO);

        Long otherHeaderId = insertHeader("TEST-IMB-0005", "ADJUSTMENT", contractId, otherRunId);
        insertLine(otherHeaderId, 1, "EXPECTED_RECEIVABLE", new BigDecimal("7000.00"), BigDecimal.ZERO);

        JournalImbalanceSearchResponse response = journalImbalanceService.findImbalances(targetRunId);

        assertThat(response.totalCount()).isEqualTo(1);
        assertThat(response.content()).extracting(JournalImbalanceItemResponse::journalHeaderId)
                .containsExactly(targetHeaderId);
    }

    @Test
    void unknownValidationRunIdThrowsNotFound() {
        assertThatThrownBy(() -> journalImbalanceService.findImbalances(999_999_999L))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(ex -> assertThat(((FgcBusinessException) ex).getErrorCode())
                        .isEqualTo(FgcErrorCode.COMMON_004));
    }

    @Test
    // 응답 DTO에 계약자·설계사 등 개인정보 식별 필드가 구조적으로 없어야 한다 —
    // 이 API는 차대 합계 대사용이라 계약 식별자 자체를 노출할 필요가 없다
    // (JournalDetailResponse#returnsBalancedHeaderWithLinesInOrder 주석: insurance_contract에는
    // 계약자 이름·주민번호 컬럼 자체가 없다).
    void responseHasNoPersonalIdentifyingFields() {
        RecordComponent[] components = JournalImbalanceItemResponse.class.getRecordComponents();

        assertThat(components).extracting(RecordComponent::getName)
                .noneMatch(name -> name.toLowerCase().contains("contract")
                        || name.toLowerCase().contains("agent")
                        || name.toLowerCase().contains("name")
                        || name.toLowerCase().contains("rrn"));
    }
}
