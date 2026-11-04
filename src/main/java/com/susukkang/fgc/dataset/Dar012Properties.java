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
 * @param contracts  계약 수(대표 5건과 한도 경계 2건 포함). 단계마다 새 DB에서 처음부터 만든다
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
    /**
     * 기준일 하한. 계약 창(기준월 2개월 전 1일~전월 말)이 2026-07-01에 갈리는 FGL02 판매버전(db/demo V3)을 넘지 않게 한다.
     * 넘으면 로더가 판매버전을 하나로 정하지 못해 멈춘다. 임용일(2024-01-01~2026-05-29)도 이 창보다 앞선다.
     */
    public static final LocalDate MIN_BASE_DATE = LocalDate.of(2026, 9, 1);

    public Dar012Properties {
        if (datasetId == null || !datasetId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,39}")) {
            throw new IllegalArgumentException(
                    "fgc.dar012.dataset-id 는 필수이며 영문·숫자·-·_ 40자 이내여야 한다: " + datasetId);
        }
        seed = seed == null ? DEFAULT_SEED : seed;
        contracts = contracts == null ? DEFAULT_CONTRACTS : contracts;
        if (contracts < Dar012Planner.MIN_CONTRACTS) {
            throw new IllegalArgumentException("fgc.dar012.contracts 는 대표 설계사 5건과 한도 경계 2건을 담는 "
                    + Dar012Planner.MIN_CONTRACTS + "건 이상이어야 한다: " + contracts);
        }
        baseDate = baseDate == null ? DEFAULT_BASE_DATE : baseDate;
        if (baseDate.isBefore(MIN_BASE_DATE)) {
            throw new IllegalArgumentException(
                    "fgc.dar012.base-date 는 " + MIN_BASE_DATE + " 이후여야 한다(계약 창이 FGL02 판매버전 경계를 넘지 않게): " + baseDate);
        }
        outputDir = outputDir == null ? Path.of("build", "dar012") : outputDir;
        runValidation = runValidation != null && runValidation;
        validationMonth = validationMonth == null ? baseDate.withDayOfMonth(1).minusMonths(1) : validationMonth.withDayOfMonth(1);
        queryRepeats = queryRepeats == null ? 50 : queryRepeats;
        if (queryRepeats < 1) {
            throw new IllegalArgumentException("fgc.dar012.query-repeats 는 1 이상이어야 한다: " + queryRepeats);
        }
    }
}
