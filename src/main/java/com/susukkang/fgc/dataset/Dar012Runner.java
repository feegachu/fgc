package com.susukkang.fgc.dataset;

import lombok.extern.slf4j.Slf4j;
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
import java.util.Map;

/**
 * DAR-012 생성기 진입점 (시드명세 §50). {@code dar012} 프로필에서만 등록되므로 기본 프로필·test·배포 환경에서는
 * 자동 실행되지 않는다. 실행 순서: 빈 DB에 Flyway 적용 → 이 실행기 → 기존 월 통합검증 배치 → 정답표 비교.
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
            verification.put("answerKeyComparison", verifier.compareWithAnswerKey(generated.plan(), properties.validationMonth(), batch.validationRunId()));
            verification.put("capStatusDistribution", verifier.capStatusDistribution(generated.plan(), properties.validationMonth(), batch.validationRunId()));
            verification.put("aggregateConsistency", verifier.checkAggregateConsistency(properties.validationMonth(), batch.validationRunId()));
            verification.put("queryTimings", verifier.measureQueries(properties.validationMonth(), batch.validationRunId(),
                    properties.queryRepeats()));
            log.info("DAR-012 검증 결과: {}", Dar012ReportWriter.writeVerification(properties.datasetId(),
                    properties.outputDir(), verification).toAbsolutePath());
        }
        log.info("DAR-012 메타데이터: {}", json.toAbsolutePath());
        // 웹 서버가 함께 떠 있으므로 적재가 끝나면 앱을 종료한다.
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    /** 테스트와 실행기가 같은 경로를 쓴다. 정책 확인 → 중복 검사 → 계획 → 적재 → 출력 순서다. */
    public record Generated(Path metadataFile, Dar012Plan plan) {}

    public static Generated generate(Dar012Loader loader, Dar012Properties p) throws java.io.IOException {
        LocalDate windowStart = p.baseDate().withDayOfMonth(1).minusMonths(2);
        LocalDate windowEnd = p.baseDate().withDayOfMonth(1).minusDays(1);
        loader.assertNotLoaded(p.datasetId());
        Dar012Loader.PolicyRefs policy = loader.readPolicy(windowStart, windowEnd);
        Dar012Plan plan = Dar012Planner.plan(p.datasetId(), p.seed(), p.contracts(), p.baseDate(), policy.inputs());
        Dar012Loader.LoadResult result = loader.load(plan, policy);
        return new Generated(Dar012ReportWriter.write(plan, p.outputDir(), result.agent01Linked()), plan);
    }
}
