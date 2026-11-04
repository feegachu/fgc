package com.susukkang.fgc.dataset;

import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.service.ContractService;
import com.susukkang.fgc.dashboard.service.DashboardService;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import com.susukkang.fgc.validation.batch.MonthlyValidationJobTrigger;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 적재한 DAR-012 기초 데이터를 기존 월 통합검증 배치(MonthlyValidationJob)의 실제 경로로 실행한다.
 * 스케줄·1,200%·분개·대사·예외는 이 배치가 만든다(파생 데이터 직접 INSERT 금지, 시드명세 §50).
 */
@Slf4j
@Component
@Profile("dar012")
public class Dar012Verifier {

    private final ValidationRunCreateService createService;
    private final MonthlyValidationJobTrigger trigger;
    private final JdbcTemplate jdbc;
    private final DashboardService dashboardService;
    private final ContractService contractService;
    private final ValidationRunRepository validationRunRepository;
    private final CapCheckService capCheckService;

    public Dar012Verifier(ValidationRunCreateService createService, MonthlyValidationJobTrigger trigger, JdbcTemplate jdbc,
                          DashboardService dashboardService, ContractService contractService,
                          ValidationRunRepository validationRunRepository, CapCheckService capCheckService) {
        this.createService = createService;
        this.trigger = trigger;
        this.jdbc = jdbc;
        this.dashboardService = dashboardService;
        this.contractService = contractService;
        this.validationRunRepository = validationRunRepository;
        this.capCheckService = capCheckService;
    }

    /**
     * 시나리오별 정답표 비교 결과. deferred 는 합치 여부를 판정하지 않고 보류한 건수다(한도 경계 시나리오).
     * divergences 는 정답표와 다른 계약번호(최대 20건).
     */
    public record ScenarioResult(String scenario, int inScope, int matched, int deferred, List<String> divergences) {}

    /** 조회 응답시간. 단위 ms. */
    public record QueryTiming(String name, int repeats, long p50, long p95, long max) {}

    /** 한도 경계 시나리오는 정답표 한도 계산식이 엔진(환급·준수공제 반영)과 달라 합치를 판정하지 않는다. */
    static boolean isDeferred(String expectedResultType) {
        return "CAP_AT_LIMIT".equals(expectedResultType) || "CAP_EXCEEDED".equals(expectedResultType);
    }

    /**
     * 한 계약의 보험사→GA 단계 1회차 대사 행 전체로 정답표 기대를 판정한다. 행 순서와 무관하다.
     * 금액차이·명세 없음은 해당 유형 행이 하나라도 있으면, 상태 시나리오는 행이 있고 전부 MATCHED 이면 합치다.
     */
    static boolean judgeReconciliation(String expectedResultType, List<String> resultTypes) {
        return switch (expectedResultType) {
            case "RECON_AMOUNT_MISMATCH" -> resultTypes.contains("AMOUNT_DIFFERENCE");
            case "RECON_MISSING_STATEMENT" -> resultTypes.contains("ACTUAL_MISSING");
            default -> !resultTypes.isEmpty() && resultTypes.stream().allMatch("MATCHED"::equals);
        };
    }

    /**
     * 정답표와 이번 월 통합검증 실행의 결과를 비교한다. 대사 결과는 이 검증 실행에 연결된 대사 실행(reconciliation_run)의
     * 보험사→GA 단계 1회차 행만, 계약 상태는 계약 현재 상태로 본다. 대상은 계약일이 검증월인 계약이다
     * (명세 지급은 계약월 1회차만 만들기 때문). 한도 경계 시나리오는 보류로 세고 상태 분포만
     * {@link #capStatusDistribution}으로 기록한다.
     */
    public List<ScenarioResult> compareWithAnswerKey(Dar012Plan plan, LocalDate month, long validationRunId) {
        LocalDate from = month.withDayOfMonth(1);
        LocalDate to = from.plusMonths(1);
        Map<String, List<String>> recon = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.contract_no, rr.result_type
                  FROM reconciliation_result rr
                  JOIN reconciliation_run r ON r.reconciliation_run_id = rr.reconciliation_run_id
                  JOIN insurance_contract c ON c.contract_id = rr.contract_id
                 WHERE r.validation_run_id = ? AND rr.match_group_key LIKE 'INSURER_TO_GA:%' AND rr.installment_no = 1
                   AND c.contract_date >= ? AND c.contract_date < ? AND starts_with(c.contract_no, 'FGC-')
                """, rs -> {
            recon.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
        }, validationRunId, from, to);
        Map<String, String> status = new LinkedHashMap<>();
        jdbc.query("SELECT contract_no, current_status FROM insurance_contract WHERE contract_date >= ? AND contract_date < ? "
                + "AND starts_with(contract_no, 'FGC-')", rs -> {
            status.put(rs.getString(1), rs.getString(2));
        }, from, to);

        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, List<String>> divergences = new LinkedHashMap<>();
        for (Dar012Plan.AnswerRow a : plan.answers()) {
            if (!status.containsKey(a.contractNo())) {
                continue;
            }
            int[] c = counts.computeIfAbsent(a.scenario(), k -> new int[3]);
            c[0]++;
            if (isDeferred(a.expectedResultType())) {
                c[2]++;
                continue;
            }
            boolean ok = judgeReconciliation(a.expectedResultType(), recon.getOrDefault(a.contractNo(), List.of()));
            if (ok && a.expectedResultType().startsWith("STATUS_") && !"STATUS_LATE_ARRIVAL".equals(a.expectedResultType())) {
                ok = a.expectedResultType().endsWith(status.get(a.contractNo()));
            }
            if (ok) {
                c[1]++;
            } else {
                List<String> d = divergences.computeIfAbsent(a.scenario(), k -> new ArrayList<>());
                if (d.size() < 20) {
                    d.add(a.contractNo());
                }
            }
        }
        List<ScenarioResult> results = new ArrayList<>();
        counts.forEach((k, c) -> results.add(new ScenarioResult(k, c[0], c[1], c[2], divergences.getOrDefault(k, List.of()))));
        return results;
    }

    /** 1,200% 시나리오 계약의 이번 검증 실행 보험사→GA 단계 엔진 판정 분포(시나리오 → 상태 → 건수). */
    public Map<String, Map<String, Long>> capStatusDistribution(Dar012Plan plan, LocalDate month, long validationRunId) {
        Map<String, String> cap = capStatusByContract(month.withDayOfMonth(1), month.withDayOfMonth(1).plusMonths(1),
                validationRunId);
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (Dar012Plan.AnswerRow a : plan.answers()) {
            String s = cap.get(a.contractNo());
            if (s != null && a.scenario().startsWith("CAP_")) {
                out.computeIfAbsent(a.scenario(), k -> new LinkedHashMap<>()).merge(s, 1L, Long::sum);
            }
        }
        return out;
    }

    private Map<String, String> capStatusByContract(LocalDate from, LocalDate to, long validationRunId) {
        Map<String, String> cap = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.contract_no, cc.result_status FROM cap_check cc JOIN insurance_contract c ON c.contract_id = cc.contract_id
                 WHERE cc.validation_run_id = ? AND cc.payment_stage = 'INSURER_TO_GA'
                   AND c.contract_date >= ? AND c.contract_date < ? AND starts_with(c.contract_no, 'FGC-')
                """, rs -> {
            cap.put(rs.getString(1), rs.getString(2));
        }, validationRunId, from, to);
        return cap;
    }

    /** 같은 1,200% 판정 건수를 홈·설계사 소계·계약 목록·원천 cap_check 네 곳에서 읽은 값. */
    public record ConsistencyCheck(String name, long home, long agentSubtotal, long contractList, long sourceChecks,
                                   long sourceContracts, boolean consistent) {}

    /** 계약 상세가 쓰는 최신 판정 조회(capCheckService.findLatest)를 원천 cap_check 표본과 대조한 결과. */
    public record DetailSample(String name, String paymentStage, int sampled, int matched) {}

    private static final int DETAIL_SAMPLE_SIZE = 20;

    /**
     * 홈 카드, 설계사별 소계, 계약 목록의 1,200% 판정 건수가 원천 cap_check 와 일치하는지 본다
     * (인수조건 중 홈·설계사 소계·계약 목록 부분. 계약 상세 게이지는 {@link #checkContractDetailSample}이 표본으로 확인한다). 홈과 설계사 소계는 판정(계약×지급단계) 건수,
     * 계약 목록은 계약별 최신 GA→FC 단계 판정을 보여 주므로 그 단계 계약 수로 센다.
     */
    public List<ConsistencyCheck> checkAggregateConsistency(LocalDate month, long validationRunId) {
        var kpis = dashboardService.summarize(month).kpis();
        var agentRows = validationRunRepository.summarizeCapByAgent(validationRunId);
        List<ConsistencyCheck> checks = new ArrayList<>();
        for (var status : List.of(CapResultStatus.VIOLATION, CapResultStatus.WARNING)) {
            long home = status == CapResultStatus.VIOLATION ? kpis.capViolation() : kpis.capWarning();
            long agent = agentRows.stream().mapToLong(r ->
                    status == CapResultStatus.VIOLATION ? r.getViolationCount() : r.getWarningCount()).sum();
            ContractSearchCondition condition = new ContractSearchCondition();
            condition.setCapResultStatus(status);
            long list = contractService.selectByCondition(condition, 1, 1).totalElements();
            long sourceChecks = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cap_check WHERE validation_run_id = ? AND result_status = ?", Long.class,
                    validationRunId, status.name());
            long sourceContracts = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT contract_id) FROM cap_check WHERE validation_run_id = ? AND result_status = ? "
                            + "AND payment_stage = 'GA_TO_FC'",
                    Long.class, validationRunId, status.name());
            checks.add(new ConsistencyCheck("CAP_" + status.name(), home, agent, list, sourceChecks, sourceContracts,
                    home == sourceChecks && agent == sourceChecks && list == sourceContracts));
        }
        return checks;
    }

    /**
     * 계약 상세(CONT-W02)의 지급단계별 1,200% 게이지가 읽는 최신 판정(capCheckService.findLatest)을 원천 cap_check 와 대조한다.
     * 두 지급단계 모두에서 상태별로 계약 ID 순 표본 최대 20건을 확인한다(전수가 아니라 표본).
     */
    public List<DetailSample> checkContractDetailSample(long validationRunId) {
        List<DetailSample> samples = new ArrayList<>();
        for (PaymentStage stage : PaymentStage.values()) {
            for (var status : List.of(CapResultStatus.VIOLATION, CapResultStatus.WARNING)) {
                List<Long> contractIds = jdbc.queryForList(
                        "SELECT contract_id FROM cap_check WHERE validation_run_id = ? AND payment_stage = ? AND result_status = ? "
                                + "ORDER BY contract_id LIMIT ?", Long.class, validationRunId, stage.name(), status.name(),
                        DETAIL_SAMPLE_SIZE);
                int matched = 0;
                for (Long contractId : contractIds) {
                    if (capCheckService.findLatest(contractId, stage)
                            .map(r -> r.result().resultStatus()).filter(status::equals).isPresent()) {
                        matched++;
                    }
                }
                samples.add(new DetailSample("CAP_" + status.name(), stage.name(), contractIds.size(), matched));
            }
        }
        return samples;
    }

    /** 대표 조회를 반복 실행해 응답시간을 잰다. 첫 호출은 워밍업으로 버린다. */
    public List<QueryTiming> measureQueries(LocalDate month, long validationRunId, int repeats) {
        List<QueryTiming> timings = new ArrayList<>();
        timings.add(time("dashboard-summary", repeats, () -> dashboardService.summarize(month)));
        ContractSearchCondition all = new ContractSearchCondition();
        timings.add(time("contract-list", repeats, () -> contractService.selectByCondition(all, 1, 20)));
        Long contractId = jdbc.queryForObject("SELECT MAX(contract_id) FROM insurance_contract", Long.class);
        timings.add(time("contract-detail", repeats, () -> contractService.selectContractDetailById(contractId)));
        timings.add(time("agent-cap-subtotal", repeats, () -> validationRunRepository.summarizeCapByAgent(validationRunId)));
        return timings;
    }

    private QueryTiming time(String name, int repeats, Supplier<?> call) {
        call.get();
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < repeats; i++) {
            long t = System.nanoTime();
            call.get();
            samples.add((System.nanoTime() - t) / 1_000_000);
        }
        Collections.sort(samples);
        return new QueryTiming(name, repeats, samples.get(samples.size() / 2),
                samples.get(Math.max(0, (int) Math.ceil(samples.size() * 0.95) - 1)), samples.get(samples.size() - 1));
    }

    /** 배치 실행 결과. 시간은 성능 기록용 측정값이라 메타데이터의 결정적 값과 분리해 보관한다. */
    public record BatchRun(long validationRunId, BatchStatus status, long elapsedMillis) {}

    public BatchRun runMonthlyValidation(Dar012Properties p) {
        Long settlementUser = jdbc.queryForObject("SELECT user_id FROM app_user WHERE login_id = 'settle01'", Long.class);
        ValidationRunRow run = createService.create(
                new CreateValidationRunCommand(p.validationMonth(), ValidationRunType.MONTHLY, settlementUser));
        long started = System.nanoTime();
        JobExecution execution = trigger.launch(run, "dar012-" + UUID.randomUUID()).join();
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        log.info("MonthlyValidationJob 완료: validationRunId={}, status={}, {}ms", run.getValidationRunId(),
                execution.getStatus(), elapsed);
        return new BatchRun(run.getValidationRunId(), execution.getStatus(), elapsed);
    }

    /** 배치가 만든 파생 데이터 건수. 이 값은 정답표 비교와 단계별 성능 기록의 규모 지표다. */
    public Map<String, Long> derivedCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("schedule_line", jdbc.queryForObject("SELECT COUNT(*) FROM schedule_line", Long.class));
        counts.put("cap_check", jdbc.queryForObject("SELECT COUNT(*) FROM cap_check", Long.class));
        counts.put("journal_header", jdbc.queryForObject("SELECT COUNT(*) FROM journal_header", Long.class));
        counts.put("reconciliation_result", jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_result", Long.class));
        counts.put("exception_case", jdbc.queryForObject("SELECT COUNT(*) FROM exception_case", Long.class));
        counts.put("arbitrage_check", jdbc.queryForObject("SELECT COUNT(*) FROM arbitrage_check", Long.class));
        return counts;
    }
}
