package com.susukkang.fgc.exceptioncase.repository;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공용 예외 저장소의 차익거래 단건 탐지 계약. #378의 소비자는 ExceptionCaseRepository를 사용한다.
 * 호출자의 쓰기 트랜잭션에 참여하며, 실패 시 계산 결과와 검출 이력이 함께 롤백된다.
 * 안정 업무키와 실행별 증거·재검출·재개방은 fgc.record_exception_detection이 관리한다.
 * 변경 전 flush, 변경 후 clear하므로 호출 이후 관리 엔티티가 필요하면 다시 조회한다.
 */
@Transactional(propagation = Propagation.MANDATORY)
public interface ExceptionCaseDetectionCommands {

    /**
     * 실행·계약·차익 결과 ID, 지급단계와 사유로 후보 예외를 등록한다.
     * 실행이 없으면 null, 재검출이면 기존 예외 ID를 반환한다.
     */
    Long insertArbitrageCandidate(Long validationRunId, Long contractId, Long arbitrageCheckId,
                                  String paymentStage, String description);

    /**
     * 기존 예외 유형·제목·사유를 유지하여 검토 필요 예외를 등록한다.
     * 실행이 없으면 null, 동일 검증월·계약·지급단계·사유의 재검출이면 기존 예외 ID를 반환한다.
     */
    Long insertArbitrageReviewCase(String exceptionType, Long validationRunId, Long contractId,
                                   Long arbitrageCheckId, String paymentStage, String title, String description);
}
