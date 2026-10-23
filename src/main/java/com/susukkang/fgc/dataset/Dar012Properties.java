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
 */
@ConfigurationProperties(prefix = "fgc.dar012")
public record Dar012Properties(String datasetId, Long seed, Integer contracts, LocalDate baseDate, Path outputDir) {

    public static final long DEFAULT_SEED = 20261118L;
    public static final int DEFAULT_CONTRACTS = 1000;
    public static final LocalDate DEFAULT_BASE_DATE = LocalDate.of(2026, 9, 10);
    /** 설계사 임용일(2024-01-01~2026-05-29)이 계약 창(기준월 2개월 전 1일~) 시작보다 앞서도록 하는 기준일 하한. */
    public static final LocalDate MIN_BASE_DATE = LocalDate.of(2026, 8, 1);

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
        if (baseDate.isBefore(MIN_BASE_DATE)) {
            throw new IllegalArgumentException(
                    "fgc.dar012.base-date 는 " + MIN_BASE_DATE + " 이후여야 한다(임용일이 계약일보다 늦어지지 않게): " + baseDate);
        }
        outputDir = outputDir == null ? Path.of("build", "dar012") : outputDir;
    }
}
