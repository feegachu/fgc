package com.susukkang.fgc.dataset;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DAR-012 생성기 진입점 (시드명세 §50). {@code dar012} 프로필에서만 등록되므로 기본 프로필·test·배포 환경에서는
 * 자동 실행되지 않는다. 실행 순서: 새 격리 DB에 Flyway 적용 → 이 실행기 → 기존 월 통합검증 배치 → 정답표 비교.
 * 신한 서버와 개발계 DB에는 적재하지 않는다(시드명세 §50). {@link Dar012Loader#assertFreshBaseline}이 막는다.
 */
@Slf4j
@Component
@Profile("dar012")
@EnableConfigurationProperties(Dar012Properties.class)
public class Dar012Runner implements ApplicationRunner {

    private final Dar012Properties properties;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;
    private final ApplicationContext context;
    private final Dar012Verifier verifier;

    public Dar012Runner(Dar012Properties properties, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                        ApplicationContext context, Dar012Verifier verifier) {
        this.verifier = verifier;
        this.properties = properties;
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Dar012Loader loader = new Dar012Loader(jdbc, new TransactionTemplate(transactionManager));
        Generated generated = generate(loader, properties);
        Path json = generated.metadataFile();
        int exitCode = 0;
        if (properties.runValidation()) {
            Dar012Verifier.BatchRun batch = verifier.runMonthlyValidation(properties);
            Map<String, Long> derived = verifier.derivedCounts();
            log.info("월 통합검증 {} / 파생 데이터 건수 {}", batch.status(), derived);
            Map<String, Object> verification = new LinkedHashMap<>();
            verification.put("datasetId", properties.datasetId());
            verification.put("contracts", properties.contracts());
            verification.put("validationMonth", properties.validationMonth().toString());
            verification.put("batchStatus", batch.status().toString());
            verification.put("batchElapsedMillis", batch.elapsedMillis());
            verification.put("derivedCounts", derived);
            // 배치가 끝나지 않았다면 파생 데이터가 부분 결과라서 정답표 비교·집계·성능 수치를 정상 결과처럼 남기지 않는다.
            boolean completed = batch.status() == BatchStatus.COMPLETED;
            verification.put("partial", !completed);
            if (completed) {
                List<Dar012Verifier.ScenarioResult> answerKey = verifier.compareWithAnswerKey(generated.plan(), properties.validationMonth(), batch.validationRunId());
                List<Dar012Verifier.ConsistencyCheck> aggregates = verifier.checkAggregateConsistency(properties.validationMonth(), batch.validationRunId());
                Dar012Verifier.ClawbackCheck clawback = verifier.checkRepresentativeClawback(generated.plan());
                List<Dar012Verifier.DetailSample> details = verifier.checkContractDetailSample(batch.validationRunId());
                verification.put("answerKeyComparison", answerKey);
                verification.put("representativeClawback", clawback);
                verification.put("aggregateConsistency", aggregates);
                verification.put("contractDetailSample", details);
                verification.put("queryTimings", verifier.measureQueries(properties.validationMonth(), batch.validationRunId(),
                        properties.queryRepeats()));
                if (hasMismatch(answerKey, aggregates, details, clawback)) {
                    exitCode = 1;
                    log.error("DAR-012 검증이 맞지 않는다. 결과 파일의 answerKeyComparison, aggregateConsistency, contractDetailSample, representativeClawback을 본다.");
                }
            } else {
                exitCode = 1;
                log.error("월 통합검증이 {} 로 끝나 정답표 비교를 건너뛴다. 결과 파일에는 partial=true 로 남긴다.", batch.status());
            }
            log.info("DAR-012 검증 결과: {}", Dar012ReportWriter.writeVerification(properties.datasetId(),
                    properties.outputDir(), verification).toAbsolutePath());
        }
        log.info("DAR-012 메타데이터: {}", json.toAbsolutePath());
        // 웹 서버가 함께 떠 있으므로 적재가 끝나면 앱을 종료한다.
        int finalExitCode = exitCode;
        System.exit(SpringApplication.exit(context, () -> finalExitCode));
    }

    /** #635 인수조건: 정답표, 홈, 설계사 소계, 계약 상세가 일치하고 대표 설계사의 환수 사례가 계산된다. */
    static boolean hasMismatch(List<Dar012Verifier.ScenarioResult> answerKey, List<Dar012Verifier.ConsistencyCheck> aggregates,
                               List<Dar012Verifier.DetailSample> details, Dar012Verifier.ClawbackCheck clawback) {
        return answerKey.stream().noneMatch(result -> result.inScope() > 0) // 한 건도 비교하지 못한 실행은 통과가 아니다
                || answerKey.stream().anyMatch(result -> result.matched() != result.inScope())
                || aggregates.stream().anyMatch(check -> !check.consistent())
                || details.stream().anyMatch(sample -> sample.matched() != sample.sampled())
                || clawback.estimatedAmount() == null || clawback.estimatedAmount().signum() <= 0
                || !clawback.reasons().isEmpty();
    }

    /** 테스트와 실행기가 같은 경로를 쓴다. 중복 검사 → 기본 시드 확인 → 출력 경로 확인 → 정책 확인 → 계획 → 적재 → 출력 순서다. */
    public record Generated(Path metadataFile, Dar012Plan plan) {}

    public static Generated generate(Dar012Loader loader, Dar012Properties p) throws java.io.IOException {
        LocalDate windowStart = p.baseDate().withDayOfMonth(1).minusMonths(2);
        LocalDate windowEnd = p.baseDate().withDayOfMonth(1).minusDays(1);
        loader.assertNotLoaded(p.datasetId());
        loader.assertFreshBaseline();
        Dar012ReportWriter.assertWritable(p.outputDir());
        Dar012Loader.PolicyRefs policy = loader.readPolicy(windowStart, windowEnd);
        Dar012Plan plan = Dar012Planner.plan(p.datasetId(), p.seed(), p.contracts(), p.baseDate(), policy.inputs());
        Dar012Loader.LoadResult result = loader.load(plan, policy);
        return new Generated(Dar012ReportWriter.write(plan, p.outputDir(), result.agent01PreviousAgentCode()), plan);
    }
}
