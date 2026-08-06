package com.susukkang.fgc.contract.dto;
import com.susukkang.fgc.common.code.ReasonCode;
import com.susukkang.fgc.contract.domain.ContractStatus;
import com.susukkang.fgc.contract.domain.DataOrigin;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 설명 : 보험계약 상태 변경 사건 정보를 저장한다.
 *
 * @author hjKang
 * @since 2026-08-05
 * @version 1.0
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractStatusEventInsertRow {
    private Long contractStatusEventId; // 계약 상태 사건 Id
    private Long contractId; // 보험계약 Id (FK)
    private Integer eventSeq; // 계약별 상태 사건 순번
    private ContractStatus previousStatus; // 변경 이전 계약상태
    private ContractStatus newStatus; // 변경 이후 계약상태
    private OffsetDateTime effectiveAt; // 상태가 실제 효력을 발생한 시각
    private OffsetDateTime receivedAt; // FGC가 상태 사건을 수신한 시각
    private OffsetDateTime processedAt; // 검증 또는 배치가 사건을 처리한 시각
    private ReasonCode reasonCode; // 계약 변경 사유 코드 , common에 있음
    private String sourceSystem; // 상태 사건을 전달한 원천 시스템
    private String sourceEventKey; // 원천 시스템의 상태 사건 고유 키
    private String sourceVersion; // 원천 데이터 또는 시스템 버전
    private DataOrigin dataOrigin; // 데이터 생성 출처 'NORMALIZED_DB','MANUAL','SEED'
    private String sourceRef; // 원천 데이터 참조 정보
    private Long registeredBy; // 등록자 userId , 자동생성아님 넣어줘야함
}