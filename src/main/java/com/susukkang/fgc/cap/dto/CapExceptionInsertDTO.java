package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 설명 : 1,200% 한도 주의 또는 위반 예외를 exception_case 테이블에 등록하기 위한 DTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapExceptionInsertDTO {
    private String exceptionKey;          // 예외 업무 고유키
    private ExceptionType exceptionType;  // 예외 유형
    private ExceptionSeverity severity;   // 심각도
    private Long validationRunId;         // 배치 검증 실행 ID
    private Long contractId;              // 관련 계약 ID
    private Long agentId;                 // 관련 설계사 ID
    private Long policyVersionId;         // 적용 정책 버전 ID
    private Long paymentId;               // 관련 지급 건 ID
    private Long capCheckId;              // 판정 근거가 된 cap_check (#331 — 예외에서 계산근거로 이동)
    private String title;                 // 예외 제목
    private String description;           // 예외 상세 및 계산 근거
}