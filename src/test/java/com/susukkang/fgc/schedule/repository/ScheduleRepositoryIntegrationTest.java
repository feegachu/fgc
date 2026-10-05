package com.susukkang.fgc.schedule.repository;

import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.PaymentCycleCode;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.common.code.ScheduleLineStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import com.susukkang.fgc.contract.service.ContractService;
import com.susukkang.fgc.schedule.code.SchedulePurpose;
import com.susukkang.fgc.schedule.code.ScheduleRegime;
import com.susukkang.fgc.schedule.dto.ScheduleDetailResponse;
import com.susukkang.fgc.schedule.dto.ScheduleGenerationResult;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse;
import com.susukkang.fgc.schedule.dto.ScheduleLineInsertDTO;
import com.susukkang.fgc.schedule.dto.ScheduleRegenResponse;
import com.susukkang.fgc.schedule.dto.ScheduleSearchCondition;
import com.susukkang.fgc.schedule.mapper.ScheduleMapper;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.validation.service.ScheduleRegenerationBatchItemService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설명 : 예상 스케줄 저장소의 JPA 전환(#371)을 실제 PostgreSQL에서 검증한다.
 * 조회 결과는 아직 운영에 남아 있는 기존 ScheduleMapper(E 담당 ValidationRunScheduleService가 사용)와
 * 같은 데이터로 비교한다. ScheduleMapper를 삭제할 때 이 비교 테스트의 기준선도 함께 정리한다.
 * 커밋이 필요한 시나리오(동시성·REQUIRES_NEW)는 월 검증 대상(ACTIVE)에서 빠지는 청약(APPLIED) 계약을 쓰고,
 * 끝나면 그 계약의 스케줄을 비활성화해 대사 대상에서도 뺀다(확정·조정 이력 행은 DB 가드로 삭제할 수 없다).
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-05
 */
@SpringBootTest(properties = {
        "fgc.batch.daily-changed-contract.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
class ScheduleRepositoryIntegrationTest {

    @Autowired private ContractService contractService;
    @Autowired private InsuranceContractRepository contractRepository;
    @Autowired private ScheduleService scheduleService;
    @Autowired private ScheduleQueryRepository queryRepository;
    @Autowired private ScheduleMapper baseline;
    @Autowired private ScheduleRegenerationBatchItemService batchItemService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Long committedContractId;

    @Test
    void readsMatchMyBatisBaselineIncludingSearchPagingAndEmptyResults() {
        inRolledBackTransaction(() -> {
            Long contractId = createContract(ContractStatus.ACTIVE);
            String contractNo = contractNo(contractId);
            Long gaHeaderId = activeHeaderId(contractId, PaymentStage.GA_TO_FC);
            // 버전 조회 비교용으로 같은 지급단계에 두 번째 버전을 만든다.
            Long regeneratedId = scheduleService.regenerateSchedules(gaHeaderId, "TEST_REGEN").getScheduleHeaderId();

            List<ScheduleSearchCondition> conditions = new ArrayList<>(List.of(
                    condition(c -> { }),
                    condition(c -> c.setContractNo(contractNo.substring(3, 12))),
                    condition(c -> c.setContractNo(contractNo)),
                    condition(c -> c.setStage(PaymentStage.INSURER_TO_GA)),
                    condition(c -> c.setRegime(ScheduleRegime.CURRENT)),
                    condition(c -> c.setStatus(ScheduleHeaderStatus.ADJUSTED)),
                    condition(c -> { c.setContractNo(contractNo); c.setStatus(ScheduleHeaderStatus.PLANNED); }),
                    condition(c -> c.setContractNo("NO-SUCH-CONTRACT")),
                    condition(c -> c.setPurpose(SchedulePurpose.COMPARISON))));
            for (ScheduleSearchCondition condition : conditions) {
                for (long offset : List.of(0L, 1L, 3L, 100_000L)) {
                    assertSame(baseline.selectByCondition(condition, 3, offset),
                            queryRepository.selectByCondition(condition, 3, offset));
                }
                assertSame(baseline.selectByCondition(condition, 100, 0),
                        queryRepository.selectByCondition(condition, 100, 0));
                assertThat(queryRepository.countByCondition(condition)).isEqualTo(baseline.countByCondition(condition));
            }
            ScheduleSearchCondition byContract = condition(c -> c.setContractNo(contractNo));
            assertSame(baseline.selectAllByCondition(byContract), queryRepository.selectAllByCondition(byContract));
            assertThat(queryRepository.selectByCondition(byContract, 3, (long) Integer.MAX_VALUE + 1)).isEmpty();

            for (Long headerId : List.of(gaHeaderId, regeneratedId, activeHeaderId(contractId, PaymentStage.INSURER_TO_GA))) {
                assertSame(baseline.selectVersionsByScheduleHeaderId(headerId),
                        queryRepository.selectVersionsByScheduleHeaderId(headerId));
                assertSame(baseline.selectScheduleDetailById(headerId), queryRepository.selectScheduleDetailById(headerId));
                assertSame(baseline.selectScheduleHeaderById(headerId), queryRepository.selectScheduleHeaderById(headerId));
                assertSame(baseline.selectScheduleLinesByScheduleId(headerId),
                        queryRepository.selectScheduleLinesByScheduleId(headerId));
            }
            assertThat(queryRepository.selectVersionsByScheduleHeaderId(regeneratedId))
                    .extracting(ScheduleHeaderResponse::getScheduleVersionNo).containsExactly(2, 1);
            for (PaymentStage stage : PaymentStage.values()) {
                assertSame(baseline.selectByContractIdAndPaymentStage(contractId, stage),
                        queryRepository.selectByContractIdAndPaymentStage(contractId, stage));
                assertThat(queryRepository.selectActiveOperationalPolicyVersionId(contractId, stage))
                        .isEqualTo(baseline.selectActiveOperationalPolicyVersionId(contractId, stage));
                assertThat(queryRepository.selectNextScheduleVersionNo(contractId, stage))
                        .isEqualTo(baseline.selectNextScheduleVersionNo(contractId, stage));
            }
            assertThat(queryRepository.selectActiveOperationalScheduleIds(contractId))
                    .isEqualTo(baseline.selectActiveOperationalScheduleIds(contractId));

            // 없는 대상은 기존처럼 null·빈 목록·1번 버전이다.
            assertThat(queryRepository.selectScheduleDetailById(-1L)).isNull();
            assertThat(queryRepository.selectScheduleHeaderById(-1L)).isNull();
            assertThat(queryRepository.selectVersionsByScheduleHeaderId(-1L)).isEmpty();
            assertThat(queryRepository.selectByContractIdAndPaymentStage(-1L, PaymentStage.GA_TO_FC)).isNull();
            assertThat(queryRepository.selectNextScheduleVersionNo(-1L, PaymentStage.GA_TO_FC)).isEqualTo(1);
            assertThat(queryRepository.selectActiveOperationalPolicyVersionId(-1L, PaymentStage.GA_TO_FC)).isNull();
        });
    }

    @Test
    void generationStoresHeaderAndLinesAndSameFlowReadsThemBack() {
        inRolledBackTransaction(() -> {
            Long contractId = createContract(ContractStatus.ACTIVE);
            for (PaymentStage stage : PaymentStage.values()) {
                ScheduleDetailResponse detail = queryRepository.selectByContractIdAndPaymentStage(contractId, stage);
                assertThat(detail.getHeader().getScheduleVersionNo()).isEqualTo(1);
                assertThat(detail.getHeader().getStatus()).isEqualTo(ScheduleHeaderStatus.PLANNED);
                assertThat(detail.getHeader().getGenerationReason()).isEqualTo("CONTRACT_CREATED");
                assertThat(detail.getLines()).isNotEmpty().allSatisfy(line -> {
                    assertThat(line.getLineStatus()).isEqualTo("PLANNED");
                    assertThat(line.getExpectedAmount().scale()).isEqualTo(2);
                });
                // 회차별로 반올림한 값을 더한 합계가 헤더 집계와 같다.
                BigDecimal sum = detail.getLines().stream().map(line -> line.getExpectedAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(detail.getHeader().getExpectedTotal()).isEqualTo(sum);
                assertThat(detail.getHeader().getLineCount()).isEqualTo(detail.getLines().size());
                assertThat(detail.getLines()).extracting(line -> line.getLineNo())
                        .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, detail.getLines().size())
                                .boxed().toList());
            }
            // 같은 정책으로 다시 생성하면 새 헤더를 만들지 않는다.
            assertThat(scheduleService.generateSchedulesByContractId(contractId))
                    .isEqualTo(new ScheduleGenerationResult(List.of(), 0));
            assertThat(headerCount(contractId)).isEqualTo(2);
        });
    }

    @Test
    void regenerationKeepsConfirmedLinesAndSwitchesTheActiveVersion() {
        inRolledBackTransaction(() -> {
            Long contractId = createContract(ContractStatus.ACTIVE);
            Long oldId = activeHeaderId(contractId, PaymentStage.GA_TO_FC);
            scheduleService.confirmSchedule(oldId);
            List<ScheduleLineInsertDTO> confirmedLines = queryRepository.selectScheduleLinesByScheduleId(oldId);
            assertThat(confirmedLines).allSatisfy(line -> assertThat(line.getLineStatus())
                    .isEqualTo(ScheduleLineStatus.CONFIRMED));
            // 확정 재호출은 멱등이다.
            assertThat(scheduleService.confirmSchedule(oldId).getHeader().getStatus())
                    .isEqualTo(ScheduleHeaderStatus.CONFIRMED);

            ScheduleRegenResponse response = scheduleService.regenerateSchedules(oldId, "TEST_REGEN");

            assertThat(response.getVersionNo()).isEqualTo(2L);
            assertThat(queryRepository.selectScheduleHeaderById(oldId)).satisfies(old -> {
                assertThat(old.getStatus()).isEqualTo(ScheduleHeaderStatus.ADJUSTED);
                assertThat(old.getActiveYn()).isFalse();
            });
            List<ScheduleLineInsertDTO> newLines =
                    queryRepository.selectScheduleLinesByScheduleId(response.getScheduleHeaderId());
            assertThat(newLines).hasSameSizeAs(confirmedLines);
            for (int index = 0; index < newLines.size(); index++) {
                ScheduleLineInsertDTO before = confirmedLines.get(index);
                ScheduleLineInsertDTO after = newLines.get(index);
                assertThat(after.getLineStatus()).isEqualTo(ScheduleLineStatus.CONFIRMED);
                assertThat(after.getExpectedAmount()).isEqualTo(before.getExpectedAmount());
                assertThat(after.getBeneficiaryAgentId()).isEqualTo(before.getBeneficiaryAgentId());
            }
            assertThat(queryRepository.selectScheduleHeaderById(response.getScheduleHeaderId()).getRegeneratedFromId())
                    .isEqualTo(oldId);
            assertThat(activeCount(contractId, PaymentStage.GA_TO_FC)).isEqualTo(1);
            // 잘못된 재생성 요청은 헤더를 남기지 않고 거절한다.
            assertThatThrownBy(() -> scheduleService.regenerateSchedules(oldId, "TEST_REGEN"))
                    .isInstanceOfSatisfying(FgcBusinessException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.SCHE_001));
            assertThat(headerCount(contractId)).isEqualTo(3);
        });
    }

    @Test
    void concurrentRegenerationCreatesExactlyOneNewActiveVersion() throws Exception {
        Long contractId = committedContract();
        Long headerId = activeHeaderId(contractId, PaymentStage.INSURER_TO_GA);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> regenerate = () -> {
            start.await();
            try {
                return scheduleService.regenerateSchedules(headerId, "TEST_CONCURRENT");
            } catch (FgcBusinessException exception) {
                return exception.getErrorCode();
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(regenerate);
            Future<Object> second = executor.submit(regenerate);
            start.countDown();
            List<Object> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(results).filteredOn(ScheduleRegenResponse.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(FgcErrorCode.SCHE_001::equals).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(activeCount(contractId, PaymentStage.INSURER_TO_GA)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT schedule_version_no FROM fgc.schedule_header
                 WHERE contract_id = ? AND payment_stage = 'INSURER_TO_GA' ORDER BY schedule_version_no
                """, Integer.class, contractId)).containsExactly(1, 2);
    }

    @Test
    void batchItemCommitsHeadersLinesAndRunLinkEvenWhenOuterTransactionRollsBack() {
        Long contractId = committedContract();
        deactivateSchedules(contractId);
        Long runId = insertValidationRun();

        List<Long> created = new ArrayList<>();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            created.addAll(batchItemService.process(contractId, runId).scheduleHeaderIds());
            throw new IllegalStateException("outer failure after the item committed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(created).hasSize(2);
        for (Long headerId : created) {
            assertThat(jdbc.queryForObject("SELECT validation_run_id FROM fgc.schedule_header WHERE schedule_header_id = ?",
                    Long.class, headerId)).isEqualTo(runId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.schedule_line WHERE schedule_header_id = ?",
                    Integer.class, headerId)).isPositive();
        }
    }

    @Test
    void batchItemRollsBackHeadersAndLinesWhenRunLinkFails() {
        Long contractId = committedContract();
        deactivateSchedules(contractId);
        int headersBefore = headerCount(contractId);

        // 존재하지 않는 실행 ID → 연결 UPDATE의 FK 위반으로 같은 REQUIRES_NEW 전체가 롤백돼야 한다.
        assertThatThrownBy(() -> batchItemService.process(contractId, -1L)).isInstanceOf(RuntimeException.class);

        assertThat(headerCount(contractId)).isEqualTo(headersBefore);
        assertThat(activeCount(contractId, PaymentStage.GA_TO_FC)).isZero();
        assertThat(activeCount(contractId, PaymentStage.INSURER_TO_GA)).isZero();
    }

    @Test
    void lineInsertIsOneStatementRegardlessOfLineCount() {
        inRolledBackTransaction(() -> {
            Long contractId = createContract(ContractStatus.ACTIVE);
            deactivateSchedules(contractId);
            Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            statistics.clear();
            long started = System.nanoTime();
            ScheduleGenerationResult result = scheduleService.generateSchedulesByContractId(contractId);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            long statements = statistics.getPrepareStatementCount();
            System.out.println("SCHEDULE_GENERATION_SQL headers=" + result.scheduleHeaderIds().size()
                    + " lines=" + result.createdLineCount() + " statements=" + statements + " elapsedMs=" + elapsedMs);
            // 헤더 INSERT와 회차 INSERT는 지급단계마다 각 1문장이다. 회차 수에 비례해 늘지 않는다.
            assertThat((long) result.createdLineCount()).isGreaterThan(statements);
            assertThat(statistics.getEntityInsertCount()).isEqualTo(result.scheduleHeaderIds().size());
        });
    }

    private void assertSame(Object expected, Object actual) {
        // OffsetDateTime의 오프셋·BigDecimal의 scale까지 같아야 한다(CSV는 toString으로 출력).
        assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
    }

    private void inRolledBackTransaction(Runnable body) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            body.run();
            status.setRollbackOnly();
        });
    }

    private Long committedContract() {
        committedContractId = new TransactionTemplate(transactionManager)
                .execute(status -> createContract(ContractStatus.APPLIED));
        return committedContractId;
    }

    @AfterEach
    void excludeCommittedContractFromReconciliation() {
        if (committedContractId != null) {
            deactivateSchedules(committedContractId);
        }
    }

    private Long createContract(ContractStatus status) {
        Long seedId = jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, "FGC-FGL01-202607-0001");
        InsuranceContract seed = contractRepository.findById(seedId).orElseThrow();
        return contractService.createContract(new ContractCreateRequest(seed.getInsurerId(),
                "SCH-" + UUID.randomUUID(), seed.getProductOfferingId(),
                seed.getContractDate(), status,
                seed.getAgentId(), seed.getOrganizationId(), PaymentCycleCode.MONTHLY,
                new BigDecimal("123457"), new BigDecimal("123457"), new BigDecimal("123457"),
                120, BigDecimal.ZERO)).contractId();
    }

    private void deactivateSchedules(Long contractId) {
        jdbc.update("UPDATE fgc.schedule_header SET active_yn = FALSE WHERE contract_id = ?", contractId);
    }

    private Long insertValidationRun() {
        LocalDate month = LocalDate.of(2097, 11, 1);
        return jdbc.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'PRE_CONFIRM', 'CREATED'
                  FROM fgc.validation_run
                 WHERE validation_month = ?
                RETURNING validation_run_id
                """, Long.class, month, month);
    }

    private String contractNo(Long contractId) {
        return jdbc.queryForObject("SELECT contract_no FROM fgc.insurance_contract WHERE contract_id = ?",
                String.class, contractId);
    }

    private Long activeHeaderId(Long contractId, PaymentStage stage) {
        return jdbc.queryForObject("""
                SELECT schedule_header_id FROM fgc.schedule_header
                 WHERE contract_id = ? AND payment_stage = ? AND schedule_purpose = 'OPERATIONAL' AND active_yn
                """, Long.class, contractId, stage.name());
    }

    private int activeCount(Long contractId, PaymentStage stage) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM fgc.schedule_header
                 WHERE contract_id = ? AND payment_stage = ? AND schedule_purpose = 'OPERATIONAL' AND active_yn
                """, Integer.class, contractId, stage.name());
    }

    private int headerCount(Long contractId) {
        return jdbc.queryForObject("SELECT count(*) FROM fgc.schedule_header WHERE contract_id = ?",
                Integer.class, contractId);
    }

    private static ScheduleSearchCondition condition(Consumer<ScheduleSearchCondition> customizer) {
        ScheduleSearchCondition condition = new ScheduleSearchCondition();
        condition.setPurpose(SchedulePurpose.OPERATIONAL);
        customizer.accept(condition);
        return condition;
    }
}
