package com.susukkang.fgc.validation.dto;

/**
 * GET /api/v1/validation-runs/active-monthly 응답 — 해당 검증월에 진행 중(CREATED/RUNNING)인
 * MONTHLY 실행이 있는지. VRUN-W01 이 [실행 생성] 버튼을 끄는 데 쓴다(화면정의서 :1379).
 */
public record ActiveMonthlyRunResponse(boolean exists) {
}
