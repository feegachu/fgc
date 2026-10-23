package com.susukkang.fgc.dataset;

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

    public Dar012Verifier(ValidationRunCreateService createService, MonthlyValidationJobTrigger trigger, JdbcTemplate jdbc,
                          DashboardService dashboardService, ContractService contractService,
                          ValidationRunRepository validationRunRepository) {
        this.createService = createService;
        this.trigger = trigger;
        this.jdbc = jdbc;
        this.dashboardService = dashboardService;
        this.contractService = contractService;
        this.validationRunRepository = validationRunRepository;
    }

    /** 시나리오별 정답표 비교 결과. divergences 는 엔진 판정이 정답표와 다른 계약번호(최대 20건). */
    public record ScenarioResult(String scenario, int inScope, int matched, List<String> divergences) {}

    /** 조회 응답시간. 단위 ms. */
    public record QueryTiming(String name, int repeats, long p50, long p95, long max) {}

    /**
     * 정답표와 월 통합검증 결과를 비교한다. 대사·상태 시나리오는 보험사→GA 단계 1회차 행의 결과로,
     * 1,200% 시나리오는 보험사→GA 단계 cap_check 가 판정되었는지로 본다. 대상은 계약일이 검증월인 계약이다
     * (명세 지급은 계약월 1회차만 만들기 때문). 한도 시나리오의 상태 분포는 엔진 한도(환급·준수공제 반영)가
     * 단순 월환산×배수와 다르므로 합치 여부가 아니라 {@link #capStatusDistribution}으로 따로 기록한다.
     */
    public List<ScenarioResult> compareWithAnswerKey(Dar012Plan plan, LocalDate month) {
        LocalDate from = month.withDayOfMonth(1);
        LocalDate to = from.plusMonths(1);
        Map<String, String> recon = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.contract_no, rr.result_type
                  FROM reconciliation_result rr JOIN insurance_contract c ON c.contract_id = rr.contract_id
                 WHERE rr.match_group_key LIKE 'INSURER_TO_GA:%' AND rr.installment_no = 1
                   AND c.contract_date >= ? AND c.contract_date < ? AND starts_with(c.contract_no, 'FGC-')
                """, rs -> {
            recon.putIfAbsent(rs.getString(1), rs.getString(2));
        }, from, to);
        Map<String, String> cap = capStatusByContract(from, to);
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
            String r = recon.get(a.contractNo());
            boolean ok = switch (a.expectedResultType()) {
                case "RECON_AMOUNT_MISMATCH" -> "AMOUNT_DIFFERENCE".equals(r);
                case "RECON_MISSING_STATEMENT" -> "ACTUAL_MISSING".equals(r);
                case "CAP_AT_LIMIT", "CAP_EXCEEDED" -> cap.get(a.contractNo()) != null;
                case "STATUS_LATE_ARRIVAL" -> "MATCHED".equals(r);
                default -> "MATCHED".equals(r) && a.expectedResultType().endsWith(status.get(a.contractNo()));
            };
            int[] c = counts.computeIfAbsent(a.scenario(), k -> new int[2]);
            c[0]++;
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
        counts.forEach((k, c) -> results.add(new ScenarioResult(k, c[0], c[1], divergences.getOrDefault(k, List.of()))));
        return results;
    }

    /** 1,200% 시나리오 계약의 보험사→GA 단계 엔진 판정 분포(시나리오 → 상태 → 건수). */
    public Map<String, Map<String, Long>> capStatusDistribution(Dar012Plan plan, LocalDate month) {
        Map<String, String> cap = capStatusByContract(month.withDayOfMonth(1), month.withDayOfMonth(1).plusMonths(1));
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (Dar012Plan.AnswerRow a : plan.answers()) {
            String s = cap.get(a.contractNo());
            if (s != null && a.scenario().startsWith("CAP_")) {
                out.computeIfAbsent(a.scenario(), k -> new LinkedHashMap<>()).merge(s, 1L, Long::sum);
            }
        }
        return out;
    }

    private Map<String, String> capStatusByContract(LocalDate from, LocalDate to) {
        Map<String, String> cap = new LinkedHashMap<>();
        jdbc.query("""
                SELECT c.contract_no, cc.result_status FROM cap_check cc JOIN insurance_contract c ON c.contract_id = cc.contract_id
                 WHERE cc.payment_stage = 'INSURER_TO_GA' AND c.contract_date >= ? AND c.contract_date < ?
                   AND starts_with(c.contract_no, 'FGC-')
                """, rs -> {
            cap.putIfAbsent(rs.getString(1), rs.getString(2));
        }, from, to);
        return cap;
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
