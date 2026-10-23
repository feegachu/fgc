package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.policy.code.FeeRegimeReasonCode;
import com.susukkang.fgc.policy.dto.FeeRegimeDecisionInput;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 설명 : 분급 체계 판정표 (운영정책서 제45조, SRC-022 부칙 제2026-3호). 입력만으로 결과를 정하는 순수 함수이며 DB를 읽지 않는다.
 * 위에서부터 처음 맞는 줄을 적용한다. 룰셋 조회가 필요한 입력은 {@link Outcome#LOOKUP_RULESET}으로 돌려준다.
 */
public final class FeeRegimeDecisionTable {

    // 부칙 제1조①·제2조: 시행일(2026-03-01) 당일 작성·변경도 "시행 후"로 본다(SRC-066, 법제처 입안·심사 기준)
    public static final LocalDate ACT_EFFECTIVE_DATE = LocalDate.of(2026, 3, 1);
    // 부칙 제2조: 판매개시일이 2027-01-01 이후인 상품부터 제4-32조⑧~⑫ 적용
    public static final LocalDate ELIGIBLE_SALES_START_FROM = LocalDate.of(2027, 1, 1);
    // 부칙 제3조①: 2028-12-31까지는 7년을 4년으로 읽는다
    public static final LocalDate FOUR_YEAR_SPECIAL_PERIOD_END = LocalDate.of(2028, 12, 31);
    // 운영정책서 제45조: 4년 특례 종료 기준일이 지급일·판매개시일·계약일 중 무엇인지 원문으로 확정 불가
    // → 계약일 기준으로 보수 처리한다(근거 없음 — 확인 필요, SRC-061 공개 시 재확인)
    public static final LocalDate FOUR_YEAR_CONTRACT_CUTOFF = LocalDate.of(2029, 1, 1);

    public static final String CURRENT = "CURRENT";
    public static final String FOUR_YEAR_2027 = "FOUR_YEAR_2027";
    public static final String SEVEN_YEAR_2029 = "SEVEN_YEAR_2029";

    public enum Outcome { NOT_APPLICABLE, REVIEW_REQUIRED, LOOKUP_RULESET }

    public record Decision(Outcome outcome, FeeRegimeReasonCode reasonCode) {
    }

    private FeeRegimeDecisionTable() {
    }

    /**
     * @param input               #535 판정 입력
     * @param coveringOfferingIds 같은 상품·판매채널에서 계약일을 판매기간에 포함하는 판매버전 ID
     */
    public static Decision decide(FeeRegimeDecisionInput input, List<Long> coveringOfferingIds) {
        Objects.requireNonNull(input, "input");
        LocalDate contractDate = Objects.requireNonNull(input.contractDate(), "contractDate");
        LocalDate salesStart = Objects.requireNonNull(input.salesStartDate(), "salesStartDate");
        LocalDate documentDate = Objects.requireNonNull(input.basicDocumentDate(), "basicDocumentDate");
        String regime = input.declaredFeeRegimeCode();

        // 1. 정의된 세 값이 아닌 체계 코드 (product_offering.fee_regime_code 에 CHECK 없음)
        if (!CURRENT.equals(regime) && !FOUR_YEAR_2027.equals(regime) && !SEVEN_YEAR_2029.equals(regime)) {
            return review(FeeRegimeReasonCode.UNKNOWN_REGIME);
        }
        // 2. 계약일이 판매기간 밖. 데이터를 고치지 않는다 (시드명세 G2)
        if (contractDate.isBefore(salesStart)
                || (input.salesEndDate() != null && contractDate.isAfter(input.salesEndDate()))) {
            return review(FeeRegimeReasonCode.CONTRACT_OUTSIDE_SALES_PERIOD);
        }
        // 2-1. 계약일을 포함하는 판매버전이 연결된 판매버전 하나뿐이 아님 (운영정책서 제45조, 판매종료일이 빈 옛 판매버전 오연결 방지)
        if (coveringOfferingIds == null || coveringOfferingIds.size() != 1
                || !coveringOfferingIds.getFirst().equals(input.productOfferingId())) {
            return review(FeeRegimeReasonCode.OFFERING_NOT_UNIQUE);
        }

        boolean eligible = !salesStart.isBefore(ELIGIBLE_SALES_START_FROM)
                && !documentDate.isBefore(ACT_EFFECTIVE_DATE);

        if (CURRENT.equals(regime)) {
            // 3. 적용례 미충족 — 계약일과 상관없이 기존 현행 경로 (부칙 제2조, SRC-025 Q4)
            if (!eligible) {
                return new Decision(Outcome.NOT_APPLICABLE, FeeRegimeReasonCode.NOT_APPLICABLE);
            }
            // 4. 개정 ⑧~⑫ 적용 대상인데 현행으로 선언됨 (부칙 제2조)
            return review(FeeRegimeReasonCode.CURRENT_BUT_ELIGIBLE);
        }

        // 5. 4년/7년 선언인데 적용례 미충족 (부칙 제2조)
        if (!eligible) {
            return review(FeeRegimeReasonCode.REGIME_NOT_ELIGIBLE);
        }
        if (FOUR_YEAR_2027.equals(regime)) {
            // 6. 특례기간 이후에 판매 개시 (부칙 제3조①)
            if (salesStart.isAfter(FOUR_YEAR_SPECIAL_PERIOD_END)) {
                return review(FeeRegimeReasonCode.FOUR_YEAR_AFTER_SPECIAL_PERIOD);
            }
            // 7. 특례 종료 기준일(미확정) 이후 계약 (운영정책서 제45조)
            if (!contractDate.isBefore(FOUR_YEAR_CONTRACT_CUTOFF)) {
                return review(FeeRegimeReasonCode.FOUR_YEAR_AFTER_SPECIAL_PERIOD);
            }
        } else if (!salesStart.isAfter(FOUR_YEAR_SPECIAL_PERIOD_END)) {
            // 8. 특례기간에는 7년을 4년으로 읽는다 (부칙 제3조①)
            return review(FeeRegimeReasonCode.SEVEN_YEAR_IN_SPECIAL_PERIOD);
        }
        // 9. 룰셋 조회
        return new Decision(Outcome.LOOKUP_RULESET, null);
    }

    private static Decision review(FeeRegimeReasonCode reasonCode) {
        return new Decision(Outcome.REVIEW_REQUIRED, reasonCode);
    }
}
