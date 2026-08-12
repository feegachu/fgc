package com.susukkang.fgc.validation.batch.daily;

/**
 * DailyChangedContractJob 안팎에서 반복 사용되는 이름 상수 모음
 */
public final class DailyChangedContractJobNames {

    public static final String JOB_NAME = "DailyChangedContractJob";

    // fgc.batch_watermark의 PK가 (job_name, step_name)이라(V2_1__policy_parameter_and_fixes.sql),
    // 워터마크를 읽고/전진시킬 때 이 값을 항상 job_name과 같이 넘겨야 한다 — job_name만 쓰면
    // 이 Job에 Step이 하나 더 생겨 워터마크 행이 늘어나는 순간 TooManyResultsException이 나거나
    // 엉뚱한 Step의 워터마크를 덮어쓰게 된다(코드리뷰 지적, 2026-08-11). V2 마이그레이션 시드
    // 행의 step_name 값과 반드시 같아야 한다.
    public static final String CHANGED_CONTRACT_STEP_NAME = "changedContractStep";

    private DailyChangedContractJobNames() {
    }
}
