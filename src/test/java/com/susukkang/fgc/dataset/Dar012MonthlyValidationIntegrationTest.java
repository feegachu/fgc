package com.susukkang.fgc.dataset;

import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.clawback.repository.ClawbackCandidateQueryRepository;
import com.susukkang.fgc.clawback.service.ClawbackCalculationService;
import com.susukkang.fgc.clawback.service.ClawbackCandidateCaptureService;
import com.susukkang.fgc.contract.service.ContractService;
import com.susukkang.fgc.dashboard.service.DashboardService;
import com.susukkang.fgc.validation.batch.MonthlyValidationJobTrigger;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성한 데이터가 실제 월 통합검증을 끝까지 통과하고 정답표와 맞는지 본다(#635 시나리오 3).
 * 생성기 자체 시험이 통과해도 직급과 납입기간이 엔진 조건과 어긋나면 배치에서만 실패한다.
 * 합성 데이터가 공유 DB에 남으면 정확한 건수를 단언하는 다른 시험이 깨지므로 DB를 분리하고, 생성기와 같은 위치만 적용한다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_dar012_validation?currentSchema=fgc",
        "spring.flyway.locations=classpath:db/migration,classpath:db/demo"
})
class Dar012MonthlyValidationIntegrationTest {
    /** 대표 설계사 사례의 계약일이 모두 7월이라 7월을 본다. 비교 대상은 계약일이 검증월인 계약이다. */
    private static final LocalDate MONTH = LocalDate.of(2026, 7, 1);

    @Autowired private ValidationRunCreateService createService;
    @Autowired private MonthlyValidationJobTrigger trigger;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DashboardService dashboardService;
    @Autowired private ContractService contractService;
    @Autowired private ValidationRunRepository validationRunRepository;
    @Autowired private CapCheckService capCheckService;
    @Autowired private ClawbackCandidateCaptureService clawbackCapture;
    @Autowired private ClawbackCandidateQueryRepository clawbackCandidates;
    @Autowired private ClawbackCalculationService clawbackCalculation;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void generatedDataCompletesTheMonthlyBatchAndMatchesTheAnswerKey(@TempDir Path tempDir) throws Exception {
        Dar012Properties properties = new Dar012Properties("D12-MV", 20261118L, 60, LocalDate.of(2026, 9, 10), tempDir, true, MONTH, 1);
        Dar012Verifier verifier = new Dar012Verifier(createService, trigger, jdbc, dashboardService, contractService,
                validationRunRepository, capCheckService, clawbackCapture, clawbackCandidates, clawbackCalculation);
        Dar012Plan plan = Dar012Runner.generate(new Dar012Loader(jdbc, new TransactionTemplate(transactionManager)), properties).plan();

        Dar012Verifier.BatchRun batch = verifier.runMonthlyValidation(properties);

        assertThat(batch.status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM validation_target t JOIN insurance_contract c ON c.contract_id = t.contract_id
                 WHERE t.validation_run_id = ? AND t.selection_status <> 'SELECTED'
                   AND c.agent_id IN (SELECT agent_id FROM agent WHERE starts_with(agent_code, 'D12-A-'))
                """, Long.class, batch.validationRunId())).isZero();
        List<Dar012Verifier.ScenarioResult> comparison = verifier.compareWithAnswerKey(plan, MONTH, batch.validationRunId());
        assertThat(comparison).extracting(Dar012Verifier.ScenarioResult::scenario)
                .contains("REP_AMOUNT_UNCONFIRMED", "STATUS_LAPSED", "STATUS_REVIVED", "STATUS_TERMINATED",
                        "CAP_AT_LIMIT", "CAP_OVER_LIMIT_1WON");
        assertThat(comparison).allSatisfy(r -> assertThat(r.matched()).as(r.scenario() + " " + r.divergences()).isEqualTo(r.inScope()));
        // 한도 경계: 엔진이 잔여 0원은 위반 아닌 WARNING으로, 1원 초과는 VIOLATION으로 낸다. 일반 계약은 NORMAL이 함께 나온다.
        assertThat(comparison).filteredOn(r -> r.scenario().startsWith("CAP_")).hasSize(2)
                .allSatisfy(r -> assertThat(r.inScope()).isEqualTo(1));
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT cc.result_status FROM cap_check cc JOIN insurance_contract c ON c.contract_id = cc.contract_id
                 WHERE cc.validation_run_id = ? AND cc.payment_stage = 'INSURER_TO_GA'
                   AND c.agent_id IN (SELECT agent_id FROM agent WHERE starts_with(agent_code, 'D12-A-'))
                """, String.class, batch.validationRunId())).contains("NORMAL", "WARNING", "VIOLATION");
        assertThat(verifier.checkAggregateConsistency(MONTH, batch.validationRunId()))
                .allSatisfy(c -> assertThat(c.consistent()).as(c.name()).isTrue());

        // 대표 해지 계약: 심은 GA→설계사 지급이 기지급액이 되고 환수 계산 엔진이 금액을 낸다.
        Dar012Verifier.ClawbackCheck clawback = verifier.checkRepresentativeClawback(plan);
        BigDecimal planted = plan.gaPayments().stream().filter(g -> g.contractNo().equals(clawback.contractNo()))
                .map(Dar012Plan.GaPaymentRow::amount).findFirst().orElseThrow();
        assertThat(clawback.paidAmount()).isEqualByComparingTo(planted);
        assertThat(clawback.estimatedAmount()).as(clawback.reasons().toString()).isPositive();
        assertThat(clawback.reasons()).isEmpty();

        // Dar012Runner가 이어서 부르는 조회도 같은 경로로 통과하고, 내부 역할 문맥이 다른 시험으로 새지 않는다.
        assertThat(verifier.checkContractDetailSample(batch.validationRunId()))
                .allSatisfy(s -> assertThat(s.matched()).as(s.name() + " " + s.paymentStage()).isEqualTo(s.sampled()));
        assertThat(verifier.measureQueries(MONTH, batch.validationRunId(), 1)).hasSize(4);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
