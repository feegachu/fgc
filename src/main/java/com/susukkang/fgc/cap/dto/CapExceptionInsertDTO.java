package com.susukkang.fgc.cap.dto;

import lombok.*;

/**
 * 설명 : 1200% 한도가 일정 위험 구간 이상이거나 초과하였을 경우 tb_exception_case 에 넣기위한 InsertDTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CapExceptionInsertDTO {
    Long exceptionCaseId; // 예외 ID
    String exceptionKey; // 예외 Key
    String exceptionType; // 예외 유형
}