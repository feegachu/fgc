package com.susukkang.fgc.validation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * validation_run 1행 INSERT 파라미터
 *
 * policySnapshotJson: FGC-FUN-041 요구사항이 명시한 스냅샷 대상은
 *   - 지급단계별 1,200% 룰셋
 *   - 상품 적용 수수료체계
 *   - 예상 해약환급률표 버전
 *   - 80% 공제 플래그
 * 무엇을 어떤 JSON 모양으로 담을지는
 * Service가 정함
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValidationRunInsertRow {
    private Long validationRunId;
    private LocalDate validationMonth;
    private Integer runNo;
    private String runType;
    private Long triggeredBy;
    private String policySnapshotJson;
}
