package com.susukkang.fgc.dataset;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * DAR-012 600인 합성 데이터셋 생성 설정 (시드명세 §50). {@code dar012} 프로필에서만 바인딩된다.
 * 실행 예: {@code gradlew generateDar012 -Pdar012.dataset-id=D12-1K -Pdar012.contracts=1000}
 *
 * @param datasetId  데이터셋 식별자. 필수이며 같은 DB에 이미 있으면 생성기가 멈춘다
 * @param seed       고정 난수 seed. 같은 seed·계약 수면 건수·합계가 같다
 * @param contracts  계약 수 단계(1천→3천→1만2천). 단계마다 새 DB에서 처음부터 만든다
 * @param baseDate   기준일. 시각 값은 이 날짜에서만 파생하고 now()를 쓰지 않는다
 * @param outputDir  {datasetId}.json, {datasetId}-answers.csv 출력 위치
 * @param runValidation  true면 적재 직후 기존 월 통합검증 배치를 실제 경로로 실행하고 정답표와 비교·성능을 기록한다
 * @param validationMonth 월 통합검증 대상 월. 기본은 기준일 직전 달
 * @param queryRepeats    조회 p95 측정 반복 횟수. 기본 50
 */
@ConfigurationProperties(prefix = "fgc.dar012")
public record Dar012Properties(String datasetId, Long seed, Integer contracts, LocalDate baseDate, Path outputDir,
                              Boolean runValidation, LocalDate validationMonth, Integer queryRepeats) {

    public static final long DEFAULT_SEED = 20261118L;
    public static final int DEFAULT_CONTRACTS = 1000;
    public static final LocalDate DEFAULT_BASE_DATE = LocalDate.of(2026, 9, 10);

    public Dar012Properties {
        if (datasetId == null || !datasetId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,39}")) {
            throw new IllegalArgumentException(
                    "fgc.dar012.dataset-id 는 필수이며 영문·숫자·-·_ 40자 이내여야 한다: " + datasetId);
        }
        seed = seed == null ? DEFAULT_SEED : seed;
        contracts = contracts == null ? DEFAULT_CONTRACTS : contracts;
        if (contracts < 5) {
            throw new IllegalArgumentException("fgc.dar012.contracts 는 대표 설계사 사례 5건 이상이어야 한다: " + contracts);
        }
        baseDate = baseDate == null ? DEFAULT_BASE_DATE : baseDate;
        outputDir = outputDir == null ? Path.of("build", "dar012") : outputDir;
        runValidation = runValidation != null && runValidation;
        validationMonth = validationMonth == null ? baseDate.withDayOfMonth(1).minusMonths(1) : validationMonth.withDayOfMonth(1);
        queryRepeats = queryRepeats == null ? 50 : queryRepeats;
    }
}
