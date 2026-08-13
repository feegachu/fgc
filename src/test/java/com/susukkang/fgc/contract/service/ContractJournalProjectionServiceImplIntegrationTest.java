package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.dto.ContractJournalResponse;
import com.susukkang.fgc.journal.domain.ConfirmedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedInsurerIncomeJournalCommand;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.service.JournalEntryDraftService;
import com.susukkang.fgc.journal.service.JournalPersistenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #108 이슈 To-do "계약 없음, 데이터 없음, 예상·실제 분개 분리, POSTED·REVERSED 이력 조회
 * 테스트".
 *
 * 분개 데이터는 이 테스트가 직접 JournalEntryDraftService(#85) + JournalPersistenceService
 * (#93)로 만든다 — saveDraft()가 REQUIRES_NEW로 커밋하므로 여기도 @Transactional을 쓰지
 * 않고(JournalPersistenceServiceImplIntegrationTest에서 겪은 @Transactional 롤백 버그와
 * 같은 함정을 피한다), @AfterEach에서 description 마커로 직접 커밋된 DELETE를 한다.
 *
 * POSTED 테스트만 예외다 — journal_header/journal_line은 status가 POSTED/REVERSED로
 * 바뀌는 순간 DB 트리거(guard_journal_header_write/guard_journal_line_write)가 DELETE는
 * 물론 DRAFT로의 역전이까지 막아버려서(V1__baseline_v2_1_2.sql:1309-1368), 한 번 POSTED로
 * 만들면 이 DB에서 영원히 못 지운다 — 실제로 처음 이 테스트를 REQUIRES_NEW 경로(saveDraft)로
 * 짰다가 journal_header_id=60이 그렇게 영구 고착됐다(#108 작업 중 발견). 그래서 그 테스트만
 * 메서드 단위 @Transactional + 순수 raw SQL INSERT로 만들어 롤백으로만 정리한다
 * (FinalizedValidationRunImmutabilityIntegrationTest와 같은 패턴).
 *
 * FGC-FGL02-202611-0001(contract_id=14)을 쓴다 — 확인 시점 기준 journal_header/
 * transaction_attribution이 전혀 없는 계약이라 "데이터 없음" 테스트와, 이 테스트가 만드는
 * 분개만 깨끗하게 담기는 "정상 조회" 테스트 둘 다에 적합하다. POSTED 테스트는 같은 이유로
 * 아직 안 쓴 FGC-FGL03-202608-0002(contract_id=18)를 따로 쓴다 — 트랜잭션이 롤백되긴
 * 하지만, 혹시 모를 상황에도 다른 테스트의 조회 결과와 절대 안 섞이게 하기 위해서다.
 */
@SpringBootTest
class ContractJournalProjectionServiceImplIntegrationTest {

    private static final String TEST_MARKER = "#108 통합테스트";
    private static final String EMPTY_CONTRACT_NO = "FGC-FGL02-202611-0001";
    private static final String POSTED_TEST_CONTRACT_NO = "FGC-FGL03-202608-0002";

    @Autowired
    private ContractJournalProjectionService contractJournalProjectionService;
    @Autowired
    private JournalEntryDraftService journalEntryDraftService;
    @Autowired
    private JournalPersistenceService journalPersistenceService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final LocalDate journalDate = LocalDate.of(2026, 8, 1);

    @AfterEach
    void cleanUp() {
        // status='DRAFT' 조건이 반드시 있어야 한다 — POSTED/REVERSED로 바뀐 행이 섞여
        // 있으면 DELETE 자체가 트리거에 막혀 예외가 나고, 그러면 DRAFT로 남은 나머지도
        // 하나도 못 지운다(#108 작업 중 실제로 이 문제로 journal_header_id=60이
        // "#108 통합테스트" 마커를 단 채 영구 고착됐다 — status 필터 없이 만들었던
        // 예전 버전의 흔적). postedJournalIsStillReturnedForHistory()는 그래서
        // @Transactional로 따로 격리해 여기 정리 대상에 아예 안 걸리게 한다.
        jdbcTemplate.update("""
                DELETE FROM fgc.journal_line WHERE journal_header_id IN (
                    SELECT journal_header_id FROM fgc.journal_header
                     WHERE description = ? AND status = 'DRAFT'
                )
                """, TEST_MARKER);
        jdbcTemplate.update(
                "DELETE FROM fgc.journal_header WHERE description = ? AND status = 'DRAFT'", TEST_MARKER);
    }

    private Long emptyContractId() {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, EMPTY_CONTRACT_NO);
    }

    @Test
    void unknownContractIdThrowsNotFound() {
        assertThatThrownBy(() -> contractJournalProjectionService.findJournalsByContractId(999_999_999L))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(ex -> assertThat(((FgcBusinessException) ex).getErrorCode())
                        .isEqualTo(FgcErrorCode.COMMON_004));
    }

    @Test
    void contractWithNoJournalsReturnsEmptyList() {
        List<ContractJournalResponse> result =
                contractJournalProjectionService.findJournalsByContractId(emptyContractId());

        assertThat(result).isEmpty();
    }

    @Test
    void journalIsGroupedIntoOneResponseWithCorrectTotalsAndLines() {
        Long contractId = emptyContractId();
        ExpectedInsurerIncomeJournalCommand command = ExpectedInsurerIncomeJournalCommand.builder()
                .scheduleLineId(System.nanoTime())
                .contractId(contractId)
                .journalDate(journalDate)
                .expectedAmount(BigDecimal.valueOf(50_000))
                .description(TEST_MARKER)
                .build();
        JournalHeaderDraft draft = journalEntryDraftService.draftExpectedInsurerIncome(command);
        journalPersistenceService.saveDraft(draft, 3L, "req-108-journal");

        List<ContractJournalResponse> result = contractJournalProjectionService.findJournalsByContractId(contractId);

        assertThat(result).hasSize(1);
        ContractJournalResponse response = result.get(0);
        assertThat(response.getJournalType()).isEqualTo("EXPECTED_INSURER_INCOME");
        assertThat(response.getSourceEntityType()).isEqualTo("SCHEDULE_LINE");
        assertThat(response.getStatus()).isEqualTo("DRAFT");
        assertThat(response.getStatusLabel()).isEqualTo("작성중");
        assertThat(response.getDebitTotal()).isEqualByComparingTo(BigDecimal.valueOf(50_000));
        assertThat(response.getCreditTotal()).isEqualByComparingTo(BigDecimal.valueOf(50_000));
        assertThat(response.getDifferenceAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        // 이슈 #108 요구사항에 지급단계가 헤더 응답 필드로 명시돼 있다 — line 값이
        // 헤더로 그대로 올라오는지 확인.
        assertThat(response.getPaymentStage()).isEqualTo("INSURER_TO_GA");
        assertThat(response.getPaymentStageLabel()).isEqualTo("원수사→GA");
        assertThat(response.getLines()).hasSize(2);
        assertThat(response.getLines()).extracting("accountCode")
                .containsExactlyInAnyOrder("EXPECTED_RECEIVABLE", "EXPECTED_INCOME");
    }

    @Test
    void expectedAndConfirmedJournalsAreKeptAsSeparateEntriesNotMerged() {
        Long contractId = emptyContractId();

        ExpectedInsurerIncomeJournalCommand expectedCommand = ExpectedInsurerIncomeJournalCommand.builder()
                .scheduleLineId(System.nanoTime())
                .contractId(contractId)
                .journalDate(journalDate)
                .expectedAmount(BigDecimal.valueOf(50_000))
                .description(TEST_MARKER)
                .build();
        journalPersistenceService.saveDraft(
                journalEntryDraftService.draftExpectedInsurerIncome(expectedCommand), 3L, "req-108-expected");

        ConfirmedFcPayoutJournalCommand confirmedCommand = ConfirmedFcPayoutJournalCommand.builder()
                .commissionTransactionId(System.nanoTime())
                .contractId(contractId)
                .beneficiaryAgentId(7L)
                .journalDate(journalDate)
                .confirmedAmount(BigDecimal.valueOf(30_000))
                .description(TEST_MARKER)
                .build();
        journalPersistenceService.saveDraft(
                journalEntryDraftService.draftConfirmedFcPayout(confirmedCommand), 3L, "req-108-confirmed");

        List<ContractJournalResponse> result = contractJournalProjectionService.findJournalsByContractId(contractId);

        // 두 유형이 하나로 합산되지 않고 각각 별개의 헤더(별도 응답 항목)로 남아야 한다 —
        // 합산됐다면 size가 1이 되거나 금액이 80,000으로 섞였을 것이다.
        assertThat(result).hasSize(2);
        assertThat(result).extracting("journalType")
                .containsExactlyInAnyOrder("EXPECTED_INSURER_INCOME", "CONFIRMED_FC_PAYOUT");
        assertThat(result).allSatisfy(r -> assertThat(r.getDebitTotal()).isEqualByComparingTo(r.getCreditTotal()));
    }

    @Test
    @Transactional
    void postedJournalIsStillReturnedForHistory() {
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, POSTED_TEST_CONTRACT_NO);
        Long expectedReceivableAccountId = jdbcTemplate.queryForObject(
                "SELECT journal_account_id FROM fgc.journal_account WHERE account_code = 'EXPECTED_RECEIVABLE'",
                Long.class);
        Long expectedIncomeAccountId = jdbcTemplate.queryForObject(
                "SELECT journal_account_id FROM fgc.journal_account WHERE account_code = 'EXPECTED_INCOME'",
                Long.class);

        // journal_header/journal_line은 INSERT는 반드시 DRAFT로만 허용되고(guard_journal_
        // header_write), 트랜잭션이 살아있는 동안은 같은 트랜잭션 안에서 DRAFT→POSTED로
        // UPDATE할 수 있다 — 이 메서드 전체가 @Transactional이라 테스트가 끝나면 이 INSERT·
        // UPDATE가 전부 롤백된다(DELETE를 쓰지 않으므로 POSTED 불변성 트리거에 걸리지 않는다).
        Long journalHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                     revision_no, contract_id, description)
                VALUES ('JV-TEST-POSTED-0001', ?, 'EXPECTED_INSURER_INCOME', 'SCHEDULE_LINE', ?, 1, ?, ?)
                RETURNING journal_header_id
                """, Long.class, journalDate, String.valueOf(System.nanoTime()), contractId, TEST_MARKER);
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line
                    (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount)
                VALUES (?, 1, ?, 50000, 0)
                """, journalHeaderId, expectedReceivableAccountId);
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line
                    (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount)
                VALUES (?, 2, ?, 0, 50000)
                """, journalHeaderId, expectedIncomeAccountId);
        jdbcTemplate.update(
                "UPDATE fgc.journal_header SET status = 'POSTED' WHERE journal_header_id = ?", journalHeaderId);

        List<ContractJournalResponse> result = contractJournalProjectionService.findJournalsByContractId(contractId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("POSTED");
        assertThat(result.get(0).getStatusLabel()).isEqualTo("기표됨");
    }
}
