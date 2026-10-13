package com.susukkang.fgc.cap;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapCheckBasisResponse;
import com.susukkang.fgc.cap.dto.CapCheckListRow;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.dto.CapCheckSearchCriteria;
import com.susukkang.fgc.cap.dto.CapCheckSearchResult;
import com.susukkang.fgc.cap.dto.RefundRateQuery;
import com.susukkang.fgc.cap.dto.RefundRateResolution;
import com.susukkang.fgc.cap.dto.ScheduleAmountView;
import com.susukkang.fgc.cap.repository.CapScheduleAmountQueryRepository;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.cap.service.ProductRefundRateResolver;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ScheduleLineStatus;
import com.susukkang.fgc.schedule.service.ScheduleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 실제 PostgreSQL 정책·스케줄·준법경영비 계산 통합 테스트
 * test 프로필의 임시 PostgreSQL에 적용된 시드데이터를 대상으로
 * CapCalculator/ProductRefundRateResolver의 JPA 조회와 계산이 맞물려 동작하는지 검증한다.
 *
 * 각 테스트가 필요한 예상 스케줄과 지급·증빙을 직접 구성하며 트랜잭션은 끝나면 롤백된다.
 *
 * @author yslee
 * @since 2026-08-10
 * @version 1.2
 */
@SpringBootTest
@Transactional
class CapCalculatorIntegrationTest {

    @Autowired
    private CapCalculator capCalculator;
    @Autowired
    private CapCheckService capCheckService;
    @Autowired
    private ProductRefundRateResolver refundRateResolver;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CapScheduleAmountQueryRepository capScheduleAmountQueryRepository;
    @Autowired
    private ScheduleService scheduleService;

    @ParameterizedTest
    @EnumSource(value = ScheduleLineStatus.class, names = {"CONFIRMED", "MATCHED", "ADJUSTED"})
    void tracksConfirmedExclusionEvidenceAcrossRepeatedRegeneration(ScheduleLineStatus status) {
        Long contractId = contractId("FGC-FGL01-202607-0001");
        Long headerId = insertOperationalScheduleWithBaseCommissionLines(
                contractId, LocalDate.of(2026, 7, 10), new BigDecimal("100"));
        Long originalLine = scheduleLineIds(headerId).getFirst();
        Long itemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item WHERE item_code='NEWCOMER_SUPPORT'", Long.class);
        jdbcTemplate.update("UPDATE fgc.schedule_line SET commission_item_id=?, line_status=? WHERE schedule_line_id=?",
                itemId, status.name(), originalLine);
        insertScheduleEvidence(contractId, originalLine, itemId, "GA_TO_FC", "EXCLUDED", "EVD-PRESERVED", true);

        for (int version = 0; version < 3; version++) {
            headerId = scheduleService.regenerateSchedules(headerId, "CONTRACT_UPDATED").getScheduleHeaderId();
            List<ScheduleAmountView> current = capScheduleAmountQueryRepository.findFirstYearScheduleAmounts(
                    contractId, "GA_TO_FC", 12);
            assertThat(current).filteredOn(line -> line.getCommissionItemId().equals(itemId))
                    .singleElement().satisfies(line -> {
                        assertThat(line.getScheduleLineId()).isNotEqualTo(originalLine);
                        assertThat(line.getEvidenceRef()).isEqualTo("EVD-PRESERVED");
                    });
            assertThat(calculatorClassification(contractId, itemId)).isEqualTo("EXCLUDED");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT schedule_line_id FROM fgc.transaction_attribution WHERE evidence_ref='EVD-PRESERVED' AND contract_id=?",
                Long.class, contractId)).isEqualTo(originalLine);
    }

    private String calculatorClassification(Long contractId, Long itemId) {
        return capCalculator.calculate(CapCalculationCommand.realtime(contractId, PaymentStage.GA_TO_FC,
                        LocalDate.of(2026, 7, 31))).details().stream()
                .filter(line -> line.commissionItemId().equals(itemId)).findFirst().orElseThrow().classification();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AMOUNT", "RECIPIENT", "ITEM", "INSTALLMENT", "DATE", "FUTURE", "PREVIOUS_HOLD", "PREVIOUS_CANCELLED", "CURRENT_HOLD", "CURRENT_CANCELLED"})
    void doesNotInheritEvidenceWhenTheCopiedOccurrenceScopeChanges(String change) {
        Long contractId = contractId("FGC-FGL01-202607-0001");
        Long header = insertOperationalScheduleWithBaseCommissionLines(
                contractId, LocalDate.of(2026, 7, 10), new BigDecimal("100"));
        Long originalLine = scheduleLineIds(header).getFirst();
        Long item = jdbcTemplate.queryForObject("SELECT commission_item_id FROM fgc.schedule_line WHERE schedule_line_id=?",
                Long.class, originalLine);
        jdbcTemplate.update("UPDATE fgc.schedule_line SET line_status='CONFIRMED' WHERE schedule_line_id=?", originalLine);
        insertScheduleEvidence(contractId, originalLine, item, "GA_TO_FC", "EXCLUDED", "EVD-ORIGINAL", true);
        jdbcTemplate.update("UPDATE fgc.schedule_header SET active_yn=false WHERE schedule_header_id=?", header);
        Long copiedHeader = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header(contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_regime, schedule_purpose, active_yn, regenerated_from_id)
                SELECT contract_id, payment_stage, policy_version_id, 2, schedule_regime, schedule_purpose, true,
                       schedule_header_id FROM fgc.schedule_header WHERE schedule_header_id=?
                RETURNING schedule_header_id
                """, Long.class, header);
        String amount = change.equals("AMOUNT") ? "expected_amount+1" : "expected_amount";
        String recipient = change.equals("RECIPIENT")
                ? "(SELECT min(agent_id) FROM fgc.agent WHERE agent_id IS DISTINCT FROM beneficiary_agent_id)" : "beneficiary_agent_id";
        String changedItem = change.equals("ITEM")
                ? "(SELECT commission_item_id FROM fgc.commission_item WHERE item_code='INCENTIVE')" : "commission_item_id";
        String installment = change.equals("INSTALLMENT") ? "installment_no+1" : "installment_no";
        String dueDate = change.equals("DATE") ? "due_date+1" : "due_date";
        String status = change.equals("FUTURE") ? "'PLANNED'" : "line_status";
        // 변경하는 SQL 조각은 위의 고정 테스트 케이스만 사용한다.
        Long copiedLine = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_line(schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, beneficiary_agent_id, basis_code, basis_amount, calculation_type,
                    rate_pct, fixed_amount, expected_amount, rounding_scale, rounding_mode, payment_condition_code,
                    line_status, source_commission_rule_id)
                SELECT ?, line_no, %s, contract_month_no, %s, %s, %s, basis_code, basis_amount, calculation_type,
                       rate_pct, fixed_amount, %s, rounding_scale, rounding_mode, payment_condition_code, %s,
                       source_commission_rule_id
                  FROM fgc.schedule_line WHERE schedule_line_id=? RETURNING schedule_line_id
                """.formatted(installment, dueDate, changedItem, recipient, amount, status),
                Long.class, copiedHeader, originalLine);
        if (change.startsWith("PREVIOUS_")) {
            jdbcTemplate.update("UPDATE fgc.schedule_line SET line_status=? WHERE schedule_line_id=?",
                    change.substring("PREVIOUS_".length()), originalLine);
        }
        if (change.startsWith("CURRENT_")) {
            jdbcTemplate.update("UPDATE fgc.schedule_line SET line_status=? WHERE schedule_line_id=?",
                    change.substring("CURRENT_".length()), copiedLine);
        }
        if (change.equals("CURRENT_CANCELLED")) {
            assertThat(capScheduleAmountQueryRepository.findFirstYearScheduleAmounts(contractId, "GA_TO_FC", 12)).isEmpty();
            return;
        }
        assertThat(capScheduleAmountQueryRepository.findFirstYearScheduleAmounts(contractId, "GA_TO_FC", 12))
                .singleElement().satisfies(line -> {
                    assertThat(line.getScheduleLineId()).isEqualTo(copiedLine);
                    assertThat(line.getEvidenceRef()).isNull();
                });
    }

    private Long contractId(String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, contractNo);
    }

    private void insertOperationalScheduleWithOneBaseCommissionLine(Long contractId, LocalDate contractDate) {
        insertOperationalScheduleWithBaseCommissionLines(
                contractId, contractDate, new BigDecimal("650000"));
    }

    private Long insertOperationalScheduleWithBaseCommissionLines(
            Long contractId,
            LocalDate contractDate,
            BigDecimal... expectedAmounts
    ) {
        Long policyVersionId = jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM fgc.policy_version WHERE policy_code='GA-CUR-2026-V1' AND status='ACTIVE'",
                Long.class);
        Long baseCommissionItemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item WHERE item_code='BASE_COMMISSION'",
                Long.class);

        // findFirstYearScheduleAmounts는 schedule_purpose='OPERATIONAL', active_yn=true인 헤더만
        // 본다. 둘 다 컬럼 기본값과 같지만, 기본값이 나중에 바뀌어도 이 테스트가 계속 맞는 헤더를
        // 만들도록 명시적으로 값을 넣는다.
        Long scheduleHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header
                    (contract_id, payment_stage, policy_version_id, schedule_version_no, schedule_regime,
                     schedule_purpose, active_yn)
                VALUES (?, 'GA_TO_FC', ?, 1, 'CURRENT', 'OPERATIONAL', true)
                RETURNING schedule_header_id
                """, Long.class, contractId, policyVersionId);

        for (int i = 0; i < expectedAmounts.length; i++) {
            int sequence = i + 1;
            jdbcTemplate.update("""
                    INSERT INTO fgc.schedule_line
                        (schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                         commission_item_id, basis_code, basis_amount, calculation_type, rate_pct, expected_amount)
                    VALUES (?, ?, ?, ?, ?, ?, 'MONTHLY_EQUIVALENT_FIRST_PREMIUM',
                            100000, 'RATE', 100.000000, ?)
                    """, scheduleHeaderId, sequence, sequence, sequence,
                    contractDate.plusMonths(i), baseCommissionItemId, expectedAmounts[i]);
        }
        return scheduleHeaderId;
    }

    // C001(일반계약)은 기본한도 1,200,000원에 산입액이 반영된다
    @Test
    void generalContractC001ReflectsIncludedAmountInBasicLimit() {
        Long id = contractId("FGC-FGL01-202607-0001"); // STD-LIFE-A, 월납 100,000원, 80% 공제 아님
        insertOperationalScheduleWithOneBaseCommissionLine(id, LocalDate.of(2026, 7, 10));

        CapCalculationResult result = capCalculator.calculate(
                CapCalculationCommand.realtime(id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        // basePremiumAmount는 월납 원액이다(×12 하지 않음) — 한도(1,200,000)와는 다른 값이다
        assertThat(result.basePremiumAmount()).isEqualByComparingTo("100000");
        assertThat(result.refund12mAmount()).isEqualByComparingTo("0");
        assertThat(result.limitAmount()).isEqualByComparingTo("1200000");
        assertThat(result.includedAmount()).isEqualByComparingTo("650000");
        assertThat(result.remainingAmount()).isEqualByComparingTo("550000");
        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.NORMAL);
    }

    // FUN-030, REG-08: 1,200% 직전·정확·초과 결과가 cap_check에 올바르게 저장되는지 검증한다.
    @Test
    void classifiesAmountsImmediatelyBelowAtAndAboveTheTwelveHundredPercentLimit() {
        Long id = contractId("FGC-FGL01-202607-0001");
        Long scheduleHeaderId = insertOperationalScheduleWithBaseCommissionLines(
                id, LocalDate.of(2026, 7, 10), new BigDecimal("1199999"));
        CapCalculationCommand command = CapCalculationCommand.realtime(
                id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10));

        CapCheckSaveResult belowSaved = capCheckService.calculateAndSave(command);
        CapCalculationResult below = belowSaved.result();
        assertThat(below.includedAmount()).isEqualByComparingTo("1199999");
        assertThat(below.resultStatus()).isNotEqualTo(CapResultStatus.VIOLATION);
        assertThat(savedResultStatus(belowSaved.capCheckId())).isNotEqualTo("VIOLATION");

        jdbcTemplate.update(
                "UPDATE fgc.schedule_line SET expected_amount = 1200000 WHERE schedule_header_id = ?",
                scheduleHeaderId);
        CapCheckSaveResult exactlyAtSaved = capCheckService.calculateAndSave(command);
        CapCalculationResult exactlyAt = exactlyAtSaved.result();
        assertThat(exactlyAt.includedAmount()).isEqualByComparingTo("1200000");
        assertThat(exactlyAt.resultStatus()).isNotEqualTo(CapResultStatus.VIOLATION);
        assertThat(savedResultStatus(exactlyAtSaved.capCheckId())).isNotEqualTo("VIOLATION");

        jdbcTemplate.update(
                "UPDATE fgc.schedule_line SET expected_amount = 1200001 WHERE schedule_header_id = ?",
                scheduleHeaderId);
        CapCheckSaveResult aboveSaved = capCheckService.calculateAndSave(command);
        CapCalculationResult above = aboveSaved.result();
        assertThat(above.includedAmount()).isEqualByComparingTo("1200001");
        assertThat(above.resultStatus()).isEqualTo(CapResultStatus.VIOLATION);
        assertThat(savedResultStatus(aboveSaved.capCheckId())).isEqualTo("VIOLATION");
    }

    // #477: 보장성이 아닌 계약은 한도를 넘어도 위반이 아니라 "적용대상 아님"으로 저장된다 (룰셋 연결 없음)
    @Test
    void savesNotApplicableForSavingsContractEvenWhenAmountExceedsTwelveHundredPercent() {
        Long id = contractId("FGC-FGL01-202607-0001");
        insertOperationalScheduleWithBaseCommissionLines(
                id, LocalDate.of(2026, 7, 10), new BigDecimal("1200001"));
        jdbcTemplate.update("""
                UPDATE fgc.product SET protection_type = 'SAVINGS'
                 WHERE product_id = (SELECT po.product_id
                                       FROM fgc.insurance_contract c
                                       JOIN fgc.product_offering po ON po.product_offering_id = c.product_offering_id
                                      WHERE c.contract_id = ?)
                """, id);

        CapCheckSaveResult saved = capCheckService.calculateAndSave(CapCalculationCommand.realtime(
                id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        assertThat(saved.result().resultStatus()).isEqualTo(CapResultStatus.NOT_APPLICABLE);
        assertThat(savedResultStatus(saved.capCheckId())).isEqualTo("NOT_APPLICABLE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cap_rule_set_id FROM fgc.cap_check WHERE cap_check_id = ?", Long.class, saved.capCheckId()))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.cap_check_detail WHERE cap_check_id = ?", Integer.class, saved.capCheckId()))
                .isZero();
    }

    // FUN-030: 계약 12개월 차는 포함하고 13개월 차는 첫해 한도에서 제외한다.
    @Test
    void includesMonthTwelveAndExcludesMonthThirteenFromTheFirstYearAmount() {
        Long id = contractId("FGC-FGL01-202607-0001");
        Long scheduleHeaderId = insertOperationalScheduleWithBaseCommissionLines(
                id,
                LocalDate.of(2026, 7, 10),
                new BigDecimal("600000"),
                new BigDecimal("700000"));
        jdbcTemplate.update("""
                UPDATE fgc.schedule_line
                   SET contract_month_no = 12, due_date = DATE '2027-06-10'
                 WHERE schedule_header_id = ? AND line_no = 1
                """, scheduleHeaderId);
        jdbcTemplate.update("""
                UPDATE fgc.schedule_line
                   SET contract_month_no = 13, due_date = DATE '2027-07-10'
                 WHERE schedule_header_id = ? AND line_no = 2
                """, scheduleHeaderId);

        CapCalculationResult result = capCalculator.calculate(
                CapCalculationCommand.realtime(
                        id, PaymentStage.GA_TO_FC, LocalDate.of(2027, 7, 10)));

        assertThat(result.includedAmount()).isEqualByComparingTo("600000");
        assertThat(result.details())
                .extracting(detail -> detail.contractMonthNo())
                .containsExactly(12);
    }

    // #39: 동일 수수료 항목의 다른 회차에 연결된 증빙을 공유하면 안 된다.
    @Test
    void evidenceIsResolvedOnlyForTheLinkedScheduleLineOccurrence() {
        Long contractId = contractId("FGC-FGL01-202607-0001");
        Long headerId = insertOperationalScheduleWithBaseCommissionLines(
                contractId, LocalDate.of(2026, 7, 10), new BigDecimal("100"), new BigDecimal("100"));
        List<Long> lineIds = jdbcTemplate.queryForList("""
                SELECT schedule_line_id
                  FROM fgc.schedule_line
                 WHERE schedule_header_id = ?
                 ORDER BY line_no
                """, Long.class, headerId);
        Long itemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item WHERE item_code = 'BASE_COMMISSION'",
                Long.class);
        Long agentId = jdbcTemplate.queryForObject(
                "SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", Long.class, contractId);
        Long paymentId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage, source_type, source_business_key, recipient_agent_id, commission_item_id,
                    settlement_month, due_date, amount, cashflow_type, status
                ) VALUES ('GA_TO_FC', 'GA_MANUAL_PAYMENT', 'IT-CAP-39-' || txid_current(), ?, ?,
                          DATE '2026-07-01', DATE '2026-07-10', 100, 'PAYMENT', 'DRAFT')
                RETURNING commission_transaction_id
                """, Long.class, agentId, itemId);
        jdbcTemplate.update("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id, agent_id,
                    schedule_line_id, attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, exclusion_type_snapshot, attribution_method,
                    allocation_basis_snapshot, evidence_ref
                ) VALUES (?, 1, 'CONTRACT', ?, ?, ?, DATE '2026-07-10', DATE '2026-07-01', 100,
                          'EXCLUDED', 'VOICE_RECORDING', 'DIRECT', '{}'::jsonb, 'EVD-LINE-1')
                """, paymentId, contractId, agentId, lineIds.get(0));
        jdbcTemplate.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?", paymentId);

        List<ScheduleAmountView> lines = capScheduleAmountQueryRepository.findFirstYearScheduleAmounts(
                contractId, PaymentStage.GA_TO_FC.name(), 12);

        assertThat(lines).hasSize(2);
        assertThat(lines).filteredOn(line -> line.getScheduleLineId().equals(lineIds.get(0)))
                .singleElement().extracting(ScheduleAmountView::getEvidenceRef).isEqualTo("EVD-LINE-1");
        assertThat(lines).filteredOn(line -> line.getScheduleLineId().equals(lineIds.get(1)))
                .singleElement().extracting(ScheduleAmountView::getEvidenceRef).isNull();
    }

    @Test
    void usesTrimmedMinimumOfOnlyMatchingConfirmedExclusionEvidence() {
        Long contractId = contractId("FGC-FGL01-202607-0001");
        Long headerId = insertOperationalScheduleWithBaseCommissionLines(
                contractId, LocalDate.of(2026, 7, 10), new BigDecimal("123.45"), new BigDecimal("678.90"));
        List<Long> lineIds = scheduleLineIds(headerId);
        Long itemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.schedule_line WHERE schedule_line_id = ?",
                Long.class, lineIds.get(0));
        Long otherItemId = jdbcTemplate.queryForObject(
                "SELECT min(commission_item_id) FROM fgc.commission_item WHERE commission_item_id <> ?",
                Long.class, itemId);
        insertScheduleEvidence(contractId, lineIds.get(0), itemId, "GA_TO_FC", "EXCLUDED", "  EVD-Z  ", true);
        insertScheduleEvidence(contractId, lineIds.get(0), itemId, "GA_TO_FC", "EXCLUDED", "  EVD-A  ", true);
        insertScheduleEvidence(contractId, lineIds.get(0), itemId, "INSURER_TO_GA", "EXCLUDED", null, true);
        insertScheduleEvidence(contractId, lineIds.get(0), otherItemId, "GA_TO_FC", "EXCLUDED", null, true);
        insertScheduleEvidence(contractId, lineIds.get(0), itemId, "GA_TO_FC", "EXCLUDED", null, false);
        insertScheduleEvidence(contractId, lineIds.get(0), itemId, "GA_TO_FC", "INCLUDED", null, true);
        insertScheduleEvidence(contractId, lineIds.get(1), itemId, "GA_TO_FC", "EXCLUDED", null, true);

        List<ScheduleAmountView> lines = capScheduleAmountQueryRepository.findFirstYearScheduleAmounts(
                contractId, "GA_TO_FC", 12);

        assertThat(lines).hasSize(2);
        assertThat(lines).filteredOn(line -> line.getScheduleLineId().equals(lineIds.get(0)))
                .singleElement().satisfies(line -> {
                    assertThat(line.getCommissionItemId()).isEqualTo(itemId);
                    assertThat(line.getContractMonthNo()).isEqualTo(1);
                    assertThat(line.getAmount()).isEqualByComparingTo("123.45");
                    assertThat(line.getEvidenceRef()).isEqualTo("EVD-A");
                });
        assertThat(lines).filteredOn(line -> line.getScheduleLineId().equals(lineIds.get(1)))
                .singleElement().extracting(ScheduleAmountView::getEvidenceRef).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void requiresEveryMatchingConfirmedExclusionToHaveNonblankEvidence(String missingEvidence) {
        Long contractId = contractId("FGC-FGL01-202607-0001");
        Long headerId = insertOperationalScheduleWithBaseCommissionLines(
                contractId, LocalDate.of(2026, 7, 10), new BigDecimal("100"));
        Long lineId = scheduleLineIds(headerId).get(0);
        Long itemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.schedule_line WHERE schedule_line_id = ?", Long.class, lineId);
        insertScheduleEvidence(contractId, lineId, itemId, "GA_TO_FC", "EXCLUDED", "EVD-VALID", true);
        insertScheduleEvidence(contractId, lineId, itemId, "GA_TO_FC", "EXCLUDED", missingEvidence, true);

        assertThat(capScheduleAmountQueryRepository.findFirstYearScheduleAmounts(contractId, "GA_TO_FC", 12))
                .singleElement().extracting(ScheduleAmountView::getEvidenceRef).isNull();
    }

    // #501: 상세 순번(detail_seq)이 조회 순서를 따르므로 실행 계획과 무관하게 항목 → 계약월차 → 행 ID 순이어야 한다.
    // 평소엔 ix_schedule_line_contract_month 인덱스 스캔이 우연히 월차 순서를 내지만, 부하·통계에 따라
    // 순차 스캔으로 바뀌면 힙 순서가 나온다. 인덱스 스캔을 끄고, 행을 역순으로 고쳐 써서 힙 순서를 뒤집은 뒤
    // 정렬 키 세 개가 각각 순서를 갈라야만 통과하는 배치를 만든다.
    @Test
    void returnsScheduleLinesInItemThenContractMonthThenLineIdOrderRegardlessOfQueryPlan() {
        jdbcTemplate.execute("SET LOCAL enable_indexscan = off");
        jdbcTemplate.execute("SET LOCAL enable_bitmapscan = off");
        Long contractId = contractId("FGC-FGL01-202607-0001");
        Long headerId = insertOperationalScheduleWithBaseCommissionLines(contractId, LocalDate.of(2026, 7, 10),
                new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("300"), new BigDecimal("400"));
        Long baseItemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item WHERE item_code = 'BASE_COMMISSION'", Long.class);
        Long incentiveItemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item WHERE item_code = 'INCENTIVE'", Long.class);
        assertThat(incentiveItemId).isGreaterThan(baseItemId);
        List<Long> lineIds = scheduleLineIds(headerId);
        // line_no → (항목, 월차): 1 → (시책, 1), 2 → (기본, 2), 3 → (기본, 1), 4 → (기본, 1). 4 → 1 순으로 고쳐 써 힙 순서를 뒤집는다.
        Object[][] layout = {{incentiveItemId, 1}, {baseItemId, 2}, {baseItemId, 1}, {baseItemId, 1}};
        for (int i = layout.length - 1; i >= 0; i--) {
            jdbcTemplate.update("UPDATE fgc.schedule_line SET commission_item_id = ?, contract_month_no = ? "
                    + "WHERE schedule_line_id = ?", layout[i][0], layout[i][1], lineIds.get(i));
        }

        assertThat(capScheduleAmountQueryRepository.findFirstYearScheduleAmounts(contractId, "GA_TO_FC", 12))
                .extracting(ScheduleAmountView::getScheduleLineId)
                .containsExactly(lineIds.get(2), lineIds.get(3), lineIds.get(1), lineIds.get(0));
    }

    @ParameterizedTest
    @CsvSource({
            "OPERATIONAL, true, PLANNED, 6, 6, true",
            "OPERATIONAL, true, PLANNED, 7, 6, false",
            "OPERATIONAL, true, PLANNED, 1, 0, false",
            "OPERATIONAL, true, CANCELLED, 1, 12, false",
            "OPERATIONAL, false, PLANNED, 1, 12, false",
            "COMPARISON, true, PLANNED, 1, 12, false",
            "SIMULATION, true, PLANNED, 1, 12, false"
    })
    void selectsActiveOperationalUncancelledLinesWithinRequestedMonthRange(
            String purpose, boolean active, String lineStatus, int month, int firstYearMonths, boolean included
    ) {
        Long contractId = contractId("FGC-FGL01-202607-0001");
        Long headerId = insertOperationalScheduleWithBaseCommissionLines(
                contractId, LocalDate.of(2026, 7, 10), new BigDecimal("100.50"));
        jdbcTemplate.update("""
                UPDATE fgc.schedule_header SET schedule_purpose = ?, scenario_code = ?, active_yn = ?
                 WHERE schedule_header_id = ?
                """, purpose, purpose.equals("OPERATIONAL") ? null : "CAP-QUERY-TEST", active, headerId);
        jdbcTemplate.update("""
                UPDATE fgc.schedule_line SET line_status = ?, contract_month_no = ? WHERE schedule_header_id = ?
                """, lineStatus, month, headerId);

        List<ScheduleAmountView> actual = capScheduleAmountQueryRepository.findFirstYearScheduleAmounts(
                contractId, "GA_TO_FC", firstYearMonths);

        assertThat(actual).hasSize(included ? 1 : 0);
        if (included) {
            assertThat(actual.get(0).getContractMonthNo()).isEqualTo(month);
            assertThat(actual.get(0).getAmount()).isEqualByComparingTo("100.50");
            assertThat(actual.get(0).getEvidenceRef()).isNull();
        }
        assertThat(capScheduleAmountQueryRepository.findFirstYearScheduleAmounts(-1L, "GA_TO_FC", 12)).isEmpty();
        assertThat(capScheduleAmountQueryRepository.findFirstYearScheduleAmounts(contractId, "INSURER_TO_GA", 12))
                .isEmpty();
    }

    private List<Long> scheduleLineIds(Long headerId) {
        return jdbcTemplate.queryForList("""
                SELECT schedule_line_id FROM fgc.schedule_line WHERE schedule_header_id = ? ORDER BY line_no
                """, Long.class, headerId);
    }

    private void insertScheduleEvidence(Long contractId, Long lineId, Long itemId, String paymentStage,
                                        String inclusionStatus, String evidence, boolean confirmed) {
        Long agentId = jdbcTemplate.queryForObject(
                "SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", Long.class, contractId);
        Long paymentId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage, source_type, source_business_key, recipient_agent_id,
                    commission_item_id, settlement_month, amount, cashflow_type, status
                ) VALUES (?, 'GA_MANUAL_PAYMENT', ?, ?, ?, DATE '2026-07-01', 100, 'PAYMENT', 'DRAFT')
                RETURNING commission_transaction_id
                """, Long.class, paymentStage, "IT-CAP-EVD-" + UUID.randomUUID(), agentId, itemId);
        jdbcTemplate.update("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id, agent_id,
                    schedule_line_id, attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, exclusion_type_snapshot, attribution_method,
                    allocation_basis_snapshot, evidence_ref
                ) VALUES (?, 1, 'CONTRACT', ?, ?, ?, DATE '2026-07-10', DATE '2026-07-01', 100,
                          ?, ?, 'DIRECT', '{}'::jsonb, ?)
                """, paymentId, contractId, agentId, lineId, inclusionStatus,
                inclusionStatus.equals("EXCLUDED") ? "NEW_AGENT_SUPPORT" : null, evidence);
        if (confirmed) {
            jdbcTemplate.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?",
                    paymentId);
        }
    }

    private String savedResultStatus(Long capCheckId) {
        return jdbcTemplate.queryForObject(
                "SELECT result_status FROM fgc.cap_check WHERE cap_check_id = ?",
                String.class,
                capCheckId
        );
    }

    // CapCheckService.calculateAndSave()는 계산 결과를 cap_check/cap_check_detail에 저장한다
    @Test
    void calculateAndSavePersistsCapCheckAndDetails() {
        Long id = contractId("FGC-FGL01-202607-0001");
        insertOperationalScheduleWithOneBaseCommissionLine(id, LocalDate.of(2026, 7, 10));

        CapCheckSaveResult saved = capCheckService.calculateAndSave(
                CapCalculationCommand.realtime(id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        assertThat(saved.capCheckId()).isNotNull();
        assertThat(saved.result().limitAmount()).isEqualByComparingTo("1200000");

        Integer detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.cap_check_detail WHERE cap_check_id = ?",
                Integer.class, saved.capCheckId());
        assertThat(detailCount).isEqualTo(1);
    }

    // #76 DailyChangedContractJob이 같은 날 FAILED→RUNNING으로 재시도할 때, 이미 성공 저장된
    // (validation_run_id, contract_id, payment_stage)를 다시 계산하려다 uq_cap_check_monthly
    // UNIQUE 위반으로 영구히 실패하던 문제의 회귀테스트(코드리뷰로 발견·수정, 2026-08-11).
    // insertCapCheck를 ON CONFLICT DO UPDATE로 바꿔서, 같은 실행 안에서 같은 계약·단계를
    // 두 번 계산해도 예외 없이 최신 값으로 덮어써야 한다.
    @Test
    void calculateAndSaveUpsertsWithinSameValidationRunInsteadOfViolatingUniqueConstraint() {
        Long id = contractId("FGC-FGL01-202607-0001");
        insertOperationalScheduleWithOneBaseCommissionLine(id, LocalDate.of(2026, 7, 10));

        Long validationRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES ('2026-07-01', 9001, 'MANUAL_CONTRACT')
                RETURNING validation_run_id
                """, Long.class);

        CapCalculationCommand command = CapCalculationCommand.dailyBatch(
                id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10), validationRunId);

        CapCheckSaveResult first = capCheckService.calculateAndSave(command);
        // 재시도 시나리오: 같은 (validationRunId, contractId, paymentStage)를 다시 계산한다.
        CapCheckSaveResult second = capCheckService.calculateAndSave(command);

        // 새 행이 아니라 같은 행을 덮어썼어야 한다(UNIQUE 위반 없이, cap_check_id 그대로 유지).
        assertThat(second.capCheckId()).isEqualTo(first.capCheckId());

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.cap_check WHERE validation_run_id = ? AND contract_id = ? AND payment_stage = 'GA_TO_FC'",
                Integer.class, validationRunId, id);
        assertThat(rowCount).isEqualTo(1);

        // 예전 detail이 남아있지 않고 이번 재계산 결과로 정확히 교체됐어야 한다.
        Integer detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.cap_check_detail WHERE cap_check_id = ?",
                Integer.class, second.capCheckId());
        assertThat(detailCount).isEqualTo(1);
    }

    // CapCheckService.findLatest()는 저장된 판정을 재계산 없이 그대로 돌려준다
    @Test
    void findLatestReturnsPersistedCapCheckWithoutRecalculating() {
        Long id = contractId("FGC-FGL01-202607-0001");
        insertOperationalScheduleWithOneBaseCommissionLine(id, LocalDate.of(2026, 7, 10));

        CapCheckSaveResult saved = capCheckService.calculateAndSave(
                CapCalculationCommand.realtime(id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        CapCheckSaveResult found = capCheckService.findLatest(id, PaymentStage.GA_TO_FC).orElseThrow();

        assertThat(found.capCheckId()).isEqualTo(saved.capCheckId());
        assertThat(found.result().limitAmount()).isEqualByComparingTo("1200000");
        assertThat(found.result().includedAmount()).isEqualByComparingTo("650000");
        assertThat(found.result().details()).hasSize(1);
    }

    // IF-API-30(FUN-030-05): search/count/summarize SQL이 실제 join·필터 조건으로 동작하는지
    // 검증한다 — 지금까지는 mock 매퍼로만 테스트돼 있었다
    @Test
    void searchReturnsPersistedCapCheckFilteredByContractNoWithSummaryCounts() {
        Long id = contractId("FGC-FGL01-202607-0001");
        insertOperationalScheduleWithOneBaseCommissionLine(id, LocalDate.of(2026, 7, 10));

        CapCheckSaveResult saved = capCheckService.calculateAndSave(
                CapCalculationCommand.realtime(id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        CapCheckSearchCriteria criteria = new CapCheckSearchCriteria(
                LocalDate.of(2026, 7, 1), "GA_TO_FC", null, null, null,
                "FGC-FGL01-202607-0001");
        CapCheckSearchResult result = capCheckService.search(criteria, 1, 20);

        assertThat(result.page().content())
                .extracting(CapCheckListRow::getCapCheckId)
                .contains(saved.capCheckId());
        assertThat(result.page().totalElements()).isEqualTo(1);
        assertThat(result.summary().normal()).isEqualTo(1);
        assertThat(result.summary().warning() + result.summary().violation()
                + result.summary().reviewRequired()).isEqualTo(0);
    }

    // IF-API-31(FUN-035, api-spec.md): 저장된 계산 스냅샷을 재계산 없이 capCheck+details[]+
    // calculationSnapshot 구조로 그대로 펼쳐 돌려줘야 한다
    @Test
    void findDetailReturnsPersistedBasisAsNestedCapCheckAndDetails() {
        Long id = contractId("FGC-FGL01-202607-0001");
        insertOperationalScheduleWithOneBaseCommissionLine(id, LocalDate.of(2026, 7, 10));

        CapCheckSaveResult saved = capCheckService.calculateAndSave(
                CapCalculationCommand.realtime(id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));

        CapCheckBasisResponse basis = capCheckService.findDetail(saved.capCheckId()).orElseThrow();

        assertThat(basis.capCheck().capCheckId()).isEqualTo(saved.capCheckId());
        assertThat(basis.capCheck().contractNo()).isEqualTo("FGC-FGL01-202607-0001");
        assertThat(basis.capCheck().limitAmount()).isEqualTo(1_200_000L);
        assertThat(basis.capCheck().includedAmount()).isEqualTo(650_000L);
        assertThat(basis.details()).hasSize(1);
        assertThat(basis.details().get(0).classificationSnapshot()).isEqualTo("INCLUDED");
        long includedSum = basis.details().stream()
                .filter(d -> "INCLUDED".equals(d.classificationSnapshot()))
                .mapToLong(com.susukkang.fgc.cap.dto.CapCheckDetailResponse::amount)
                .sum();
        assertThat(includedSum).isEqualTo(basis.capCheck().includedAmount());
    }

    // 존재하지 않는 capCheckId는 매퍼까지 실제로 태워도 빈 결과를 돌려줘야 한다(컨트롤러에서 404로 매핑)
    @Test
    void findDetailReturnsEmptyForNonExistentCapCheckId() {
        assertThat(capCheckService.findDetail(-1L)).isEmpty();
    }

    // A1(80% 공제대상 상품)은 12차월 예상 해약환급금을 수수료등에 합산한다(= 한도에서 뺀다, #468)
    // FGC-FGL02-202601-0001 : STD-LIFE-B(80% 공제 대상), 월납 100,000원, 240개월납, 계약일 2026-01-15
    // GA_TO_FC 1,200% 룰셋은 2026-07-01부터 적용되므로(REG-CAP-GA-2026-V1), 이 계약은
    // INSURER_TO_GA 단계로 검증한다. 준법경영비 3% 공제(REG-10, 제4-32조제14항)는 2027.1.1부터

    // 시행이라 2026-01-15 계약에는 아직 적용되지 않는다(REG-CAP-INS-2021-V2, 공제 0%, V6_1).
    @Test
    void standardDeduction80ProductA1SubtractsMonth12RefundFromLimit() {
        Long id = contractId("FGC-FGL02-202601-0001");

        CapCalculationResult result = capCalculator.calculate(
                CapCalculationCommand.realtime(id, PaymentStage.INSURER_TO_GA, LocalDate.of(2026, 1, 15)));

        // seed 공식: GREATEST(0,(12-2)*2.4) = 24.0% → 1,200,000 × 24% = 288,000
        assertThat(result.refund12mAmount()).isEqualByComparingTo("288000");
        // 준법경영비 3% 공제는 2027.1.1부터 시행이라 2026년 계약에는 적용되지 않는다
        assertThat(result.complianceDeductionAmount()).isEqualByComparingTo("0");
        // 산입 + 288,000 ≤ 1,200,000 → 한도 912,000
        assertThat(result.limitAmount()).isEqualByComparingTo("912000");
        assertThat(result.refundRateTableId()).isNotNull();
    }


    // REG-10(준법경영비 3% 공제)은 2027.1.1 이후 체결된 원수사→GA 계약부터 적용된다(REG-CAP-INS-2027-V1).
    // FGC-FGL01-202703-0001 : STD-LIFE-A(80% 공제 아님), 월납 100,000원, 계약일 2027-03-02
    // 공제 기준액은 한도가 아니라 월납 원액이다(feature/18 반영) — 3% × 100,000 = 3,000.
    @Test
    void complianceDeductionAppliesFromContractsDatedOnOrAfter20270101() {
        Long id = contractId("FGC-FGL01-202703-0001");

        CapCalculationResult result = capCalculator.calculate(
                CapCalculationCommand.realtime(
                        id,
                        PaymentStage.INSURER_TO_GA,
                        LocalDate.of(2027, 3, 2),
                        new BigDecimal("3000")
                ));

        assertThat(result.refund12mAmount()).isEqualByComparingTo("0");
        assertThat(result.complianceDeductionAmount()).isEqualByComparingTo("3000");
        // 준법경영비는 수수료등 계산에서 제외(⑭) → 산입 − 3,000 ≤ 1,200,000 → 한도 1,203,000
        assertThat(result.limitAmount()).isEqualByComparingTo("1203000");
    }

    // ProductRefundRateResolver는 STD-LIFE-B의 12차월 환급률과 표버전을 돌려준다
    @Test
    void resolverReturnsMonth12RateAndTableVersionForStdLifeB() {
        Long insurerId = jdbcTemplate.queryForObject(
                "SELECT insurer_id FROM fgc.insurer WHERE insurer_code='FGL02'", Long.class);
        Long productId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM fgc.product WHERE standard_product_code='STD-LIFE-B'", Long.class);

        Optional<RefundRateResolution> resolution = refundRateResolver.resolve(
                new RefundRateQuery(insurerId, productId, 240, "FACE_TO_FACE", LocalDate.of(2026, 1, 15)));

        assertThat(resolution).isPresent();
        assertThat(resolution.get().month12RatePct()).isEqualByComparingTo("24.000000");
        assertThat(resolution.get().versionNo()).isEqualTo(1);
    }

    // 계약일이 다른 2026/2027 계약도 같은 엔진으로 동일 기본한도를 계산한다
    @Test
    void calculatesSameBasicLimitForContractsInDifferentYears() {
        // C001(2026-07-10)과 G1(2027-03-02)은 둘 다 STD-LIFE-A · 월납 100,000원이다.
        Long c2026 = contractId("FGC-FGL01-202607-0001");
        Long c2027 = contractId("FGC-FGL01-202703-0001");

        CapCalculationResult r2026 = capCalculator.calculate(
                CapCalculationCommand.realtime(c2026, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 10)));
        CapCalculationResult r2027 = capCalculator.calculate(
                CapCalculationCommand.realtime(c2027, PaymentStage.GA_TO_FC, LocalDate.of(2027, 3, 2)));

        assertThat(r2026.limitAmount()).isEqualByComparingTo(r2027.limitAmount());
        assertThat(r2027.limitAmount()).isEqualByComparingTo("1200000");
    }
}
