package com.susukkang.fgc.policy.dto;

import java.time.LocalDate;

/**
 * 설명 : 분급 수수료체계 판정에 필요한 계약·판매버전 값을 DB 원본 그대로 담는 입력 모델
 * 판매채널은 판정 입력이 아니므로 담지 않으며(SRC-036, 운영정책서 제45조), 값을 고치거나 추정하지 않는다.
 *
 * @param contractId             계약 ID
 * @param contractDate           계약일
 * @param productOfferingId      상품 판매버전 ID
 * @param offeringVersion        판매버전 이름
 * @param salesStartDate         판매개시일
 * @param salesEndDate           판매종료일 (없으면 null)
 * @param basicDocumentVersion   기초서류 버전
 * @param basicDocumentDate      기초서류 작성/변경일
 * @param declaredFeeRegimeCode  판매버전이 선언한 수수료체계 코드 (정의된 세 값 밖의 코드도 그대로 전달)
 */
public record FeeRegimeDecisionInput(
        Long contractId,
        LocalDate contractDate,
        Long productOfferingId,
        String offeringVersion,
        LocalDate salesStartDate,
        LocalDate salesEndDate,
        String basicDocumentVersion,
        LocalDate basicDocumentDate,
        String declaredFeeRegimeCode
) {
}
