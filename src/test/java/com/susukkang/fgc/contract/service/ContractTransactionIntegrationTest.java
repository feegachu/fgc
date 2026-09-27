package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.PaymentCycleCode;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import com.susukkang.fgc.schedule.service.ScheduleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 계약 서비스의 실제 PostgreSQL 저장과 트랜잭션 종료 후 롤백 결과를 검증한다.
 * 실패 테스트는 테스트 자체를 @Transactional로 감싸지 않는다.
 * Spy는 실제 스케줄·한도·감사 저장 후에만 오류를 주입하며 Repository는 대체하지 않는다.
 */
@SpringBootTest(properties = "fgc.batch.daily-changed-contract.enabled=false")
class ContractTransactionIntegrationTest {

    @Autowired private ContractService contractService;
    @Autowired private InsuranceContractRepository contractRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private ScheduleService scheduleService;
    @MockitoSpyBean private CapCheckService capCheckService;
    @MockitoSpyBean private CapCalculator capCalculator;
    @MockitoSpyBean private AuditLogService auditLogService;

    private Long observedContractId;
    private Map<String, List<String>> stateBeforeFailure;
    private BigDecimal premiumBeforeFailure;
    private List<BigDecimal> scheduleBasisBeforeFailure;

    enum FailurePoint { SCHEDULE_BUSINESS, CAP_BUSINESS, CAP_SQL, AUDIT_SQL }

    @ParameterizedTest(name = "등록 후 {0}: 모든 신규 행 롤백")
    @EnumSource(FailurePoint.class)
    void creationFailureRollsBackContractAndAllDependentWrites(FailurePoint point) {
        ContractCreateRequest request = createRequest();
        injectFailure(point, true);

        assertThatThrownBy(() -> contractService.createContract(request))
                .isInstanceOf(expectedFailure(point));

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertWritesReachedFailurePoint(point);
        assertThat(premiumBeforeFailure).isEqualByComparingTo(request.getFirstPremiumAmount());
        assertThat(scheduleBasisBeforeFailure).isNotEmpty().allSatisfy(
                amount -> assertThat(amount).isEqualByComparingTo(request.getMonthlyEquivalentFirstPremium()));
        assertThat(snapshot(observedContractId).values()).allSatisfy(rows -> assertThat(rows).isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, request.getContractNo())).isZero();
    }

    @ParameterizedTest(name = "수정 후 {0}: 기존 계약·스케줄·한도·이력 복원")
    @EnumSource(FailurePoint.class)
    void updateFailureRestoresCommittedContractAndDependentRows(FailurePoint point) {
        InsuranceContract original = seedContract();
        Long contractId = original.getContractId();
        Map<String, List<String>> before = snapshot(contractId);
        ContractUpdateRequest request = updateRequest(original);
        injectFailure(point, false);

        assertThatThrownBy(() -> contractService.updateContract(contractId, request))
                .isInstanceOf(expectedFailure(point));

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(observedContractId).isEqualTo(contractId);
        assertWritesReachedFailurePoint(point);
        // JPA flush 후 MyBatis가 새 보험료로 스케줄을 재생성한 상태에서 실패해야 한다.
        assertThat(premiumBeforeFailure).isEqualByComparingTo(request.getFirstPremiumAmount());
        assertThat(scheduleBasisBeforeFailure).isNotEmpty().allSatisfy(
                amount -> assertThat(amount).isEqualByComparingTo(request.getMonthlyEquivalentFirstPremium()));
        assertThat(stateBeforeFailure.get("schedule_header").size())
                .isGreaterThan(before.get("schedule_header").size());
        assertThat(stateBeforeFailure.get("contract_financial_snapshot").size())
                .isGreaterThan(before.get("contract_financial_snapshot").size());
        if (point != FailurePoint.SCHEDULE_BUSINESS) {
            assertThat(stateBeforeFailure.get("cap_check").size()).isGreaterThan(before.get("cap_check").size());
        }
        if (point == FailurePoint.AUDIT_SQL) {
            assertThat(stateBeforeFailure.get("audit_log").size()).isGreaterThan(before.get("audit_log").size());
        }
        assertThat(snapshot(contractId)).isEqualTo(before);
    }

    @Test
    void missingCapRulePreservesCreatedContractAndReviewCasesWithoutRollbackOnly() {
        ContractCreateRequest request = createRequest();
        injectMissingCapRule();
        // 정상 반환 경로의 데이터는 append-only이므로 확인 후 테스트 소유 트랜잭션으로 정리한다.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Long id = contractService.createContract(request).contractId();
            observedContractId = id;
            Map<String, List<String>> saved = snapshot(id);
            assertThat(saved.get("insurance_contract")).hasSize(1);
            assertThat(saved.get("contract_financial_snapshot")).hasSize(1);
            assertThat(saved.get("contract_status_event")).hasSize(1);
            assertThat(saved.get("schedule_header")).hasSize(2);
            assertThat(saved.get("cap_check")).isEmpty();
            assertThat(saved.get("exception_case")).hasSize(2);
            assertThat(saved.get("audit_log")).hasSize(1);
            assertThat(status.isRollbackOnly()).isFalse();
            status.setRollbackOnly();
        });
        assertThat(snapshot(observedContractId).values()).allSatisfy(rows -> assertThat(rows).isEmpty());
    }

    @Test
    void missingCapRulePreservesUpdatedContractAndReviewCasesWithoutRollbackOnly() {
        InsuranceContract original = seedContract();
        Long id = original.getContractId();
        Map<String, List<String>> before = snapshot(id);
        ContractUpdateRequest request = updateRequest(original);
        injectMissingCapRule();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            contractService.updateContract(id, request);
            Map<String, List<String>> saved = snapshot(id);
            assertThat(readPremium(id)).isEqualByComparingTo(request.getFirstPremiumAmount());
            assertThat(saved.get("schedule_header").size()).isGreaterThan(before.get("schedule_header").size());
            assertThat(saved.get("cap_check")).isEqualTo(before.get("cap_check"));
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fgc.exception_case
                     WHERE contract_id = ? AND description = 'contract transaction test: missing cap rule'
                    """, Long.class, id)).isEqualTo(2);
            assertThat(saved.get("audit_log").size()).isGreaterThan(before.get("audit_log").size());
            assertThat(status.isRollbackOnly()).isFalse();
            status.setRollbackOnly();
        });
        assertThat(snapshot(id)).isEqualTo(before);
    }

    private void injectMissingCapRule() {
        // 계산기에서 발생한 업무 예외가 실제 CapCheckService 트랜잭션 경계를 통과한다.
        doThrow(new FgcBusinessException(FgcErrorCode.CAP_004, "contractId", Map.of(),
                "contract transaction test: missing cap rule"))
                .when(capCalculator).calculate(any());
    }

    private void injectFailure(FailurePoint point, boolean creation) {
        Answer<Object> afterRealWrite = invocation -> {
            invocation.callRealMethod();
            Long contractId = switch (point) {
                case SCHEDULE_BUSINESS -> invocation.getArgument(0);
                case CAP_BUSINESS, CAP_SQL ->
                        ((com.susukkang.fgc.cap.dto.CapCalculationCommand) invocation.getArgument(0)).contractId();
                case AUDIT_SQL -> Long.valueOf(((AuditLogService.AuditEvent) invocation.getArgument(0)).entityId());
            };
            observedContractId = contractId;
            stateBeforeFailure = snapshot(contractId);
            premiumBeforeFailure = readPremium(contractId);
            scheduleBasisBeforeFailure = jdbc.queryForList("""
                    SELECT l.basis_amount FROM fgc.schedule_line l
                      JOIN fgc.schedule_header h USING (schedule_header_id)
                     WHERE h.contract_id = ? AND h.active_yn
                       AND l.basis_code = 'MONTHLY_EQUIVALENT_FIRST_PREMIUM'
                    """, BigDecimal.class, contractId);
            if (point == FailurePoint.CAP_SQL || point == FailurePoint.AUDIT_SQL) {
                // 실제 DB 유일키 위반: 앞서 저장한 스냅샷을 중복 삽입하여 저장 오류를 만든다.
                jdbc.update("""
                        INSERT INTO fgc.contract_financial_snapshot
                               (contract_id, as_of_date, contract_month_no, cumulative_paid_premium, surrender_value_type)
                        SELECT contract_id, as_of_date, contract_month_no, cumulative_paid_premium, surrender_value_type
                          FROM fgc.contract_financial_snapshot WHERE contract_id = ? LIMIT 1
                        """, contractId);
                throw new AssertionError("스냅샷 중복 저장이 실패해야 한다");
            }
            throw new FgcBusinessException(FgcErrorCode.COMMON_500);
        };
        switch (point) {
            case SCHEDULE_BUSINESS -> {
                if (creation) {
                    doAnswer(afterRealWrite).when(scheduleService).generateSchedulesByContractId(anyLong());
                } else {
                    doAnswer(afterRealWrite).when(scheduleService).regenerateContractSchedules(anyLong(), anyString());
                }
            }
            case CAP_BUSINESS, CAP_SQL -> doAnswer(afterRealWrite).when(capCheckService).calculateAndSave(any());
            case AUDIT_SQL -> doAnswer(afterRealWrite).when(auditLogService).record(any());
        }
    }

    private Class<? extends RuntimeException> expectedFailure(FailurePoint point) {
        return point == FailurePoint.CAP_SQL || point == FailurePoint.AUDIT_SQL
                ? DataIntegrityViolationException.class : FgcBusinessException.class;
    }

    private void assertWritesReachedFailurePoint(FailurePoint point) {
        assertThat(observedContractId).isNotNull();
        assertThat(stateBeforeFailure.get("insurance_contract")).hasSize(1);
        assertThat(stateBeforeFailure.get("contract_financial_snapshot")).isNotEmpty();
        assertThat(stateBeforeFailure.get("schedule_header").size()).isGreaterThanOrEqualTo(2);
        assertThat(stateBeforeFailure.get("schedule_line")).isNotEmpty();
        if (point != FailurePoint.SCHEDULE_BUSINESS) {
            assertThat(stateBeforeFailure.get("cap_check")).isNotEmpty();
            assertThat(stateBeforeFailure.get("cap_check_detail")).isNotEmpty();
        }
        if (point == FailurePoint.AUDIT_SQL) {
            assertThat(stateBeforeFailure.get("contract_status_event")).isNotEmpty();
            assertThat(stateBeforeFailure.get("audit_log")).isNotEmpty();
        }
    }

    private BigDecimal readPremium(Long id) {
        return jdbc.queryForObject("SELECT first_premium_amount FROM fgc.insurance_contract WHERE contract_id = ?",
                BigDecimal.class, id);
    }

    private Map<String, List<String>> snapshot(Long id) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : List.of("insurance_contract", "contract_financial_snapshot", "contract_status_event",
                "schedule_header", "cap_check", "exception_case")) {
            result.put(table, jdbc.queryForList("SELECT to_jsonb(t)::text FROM fgc." + table
                    + " t WHERE contract_id = ? ORDER BY to_jsonb(t)::text", String.class, id));
        }
        result.put("schedule_line", jdbc.queryForList("""
                SELECT to_jsonb(l)::text FROM fgc.schedule_line l JOIN fgc.schedule_header h USING (schedule_header_id)
                 WHERE h.contract_id = ? ORDER BY to_jsonb(l)::text
                """, String.class, id));
        result.put("cap_check_detail", jdbc.queryForList("""
                SELECT to_jsonb(d)::text FROM fgc.cap_check_detail d JOIN fgc.cap_check c USING (cap_check_id)
                 WHERE c.contract_id = ? ORDER BY to_jsonb(d)::text
                """, String.class, id));
        result.put("audit_log", jdbc.queryForList("""
                SELECT to_jsonb(a)::text FROM fgc.audit_log a
                 WHERE entity_type = 'CONTRACT' AND entity_id = ? ORDER BY to_jsonb(a)::text
                """, String.class, String.valueOf(id)));
        return result;
    }

    private InsuranceContract seedContract() {
        Long id = jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, "FGC-FGL01-202607-0001");
        return contractRepository.findById(id).orElseThrow();
    }

    private ContractCreateRequest createRequest() {
        InsuranceContract seed = seedContract();
        return new ContractCreateRequest(seed.getInsurerId(), "TX-" + UUID.randomUUID(),
                seed.getProductOfferingId(), seed.getContractDate(), ContractStatus.ACTIVE,
                seed.getAgentId(), seed.getOrganizationId(), PaymentCycleCode.MONTHLY,
                new BigDecimal("123000"), new BigDecimal("123000"), new BigDecimal("123000"),
                120, BigDecimal.ZERO);
    }

    private ContractUpdateRequest updateRequest(InsuranceContract seed) {
        BigDecimal premium = seed.getFirstPremiumAmount().add(new BigDecimal("1000"));
        // 날짜도 바꿔 수정 트랜잭션이 추가한 재무 스냅샷의 롤백을 검증한다.
        LocalDate date = seed.getContractDate().plusDays(1);
        return new ContractUpdateRequest(seed.getContractNo(), seed.getInsurerId(), seed.getProductOfferingId(),
                date, seed.getCurrentStatus(), seed.getAgentId(), seed.getOrganizationId(),
                seed.getPaymentCycleCode(), premium, premium, premium, seed.getPaymentTermMonths(),
                seed.getStandardSurrenderDeductionAmount());
    }
}
