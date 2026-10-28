package com.susukkang.fgc.policy.code;

/**
 * 설명 : 분급 체계 판정 사유 코드 (운영정책서 제45조 판정표, #536)
 */
public enum FeeRegimeReasonCode {
    UNKNOWN_REGIME,                  // 선언 체계가 정의된 세 값이 아님
    CONTRACT_OUTSIDE_SALES_PERIOD,   // 계약일이 판매기간 밖
    OFFERING_NOT_UNIQUE,             // 계약일을 포함하는 판매버전이 연결된 하나뿐이 아님
    NOT_APPLICABLE,                  // 현행 판매버전 + 적용례 미충족
    CURRENT_BUT_ELIGIBLE,            // 현행으로 선언됐으나 개정 적용 대상
    REGIME_NOT_ELIGIBLE,             // 4년/7년으로 선언됐으나 적용례 미충족
    FOUR_YEAR_AFTER_SPECIAL_PERIOD,  // 4년 특례 종료 이후
    SEVEN_YEAR_IN_SPECIAL_PERIOD,    // 특례기간(4년으로 읽는 기간)에 7년 선언
    RULESET_MISSING,                 // 적용 가능한 룰셋 없음
    RULESET_AMBIGUOUS,               // 적용 가능한 룰셋이 둘 이상
    RULESET_FLAGGED,                 // 룰셋이 경계사례(review_required_yn)로 표시됨
    RULESET_LIMIT_MISSING,           // 선택된 룰셋에 필수 한도 행이 없음 (#537)
    RULESET_SELECTED                // 룰셋 하나를 선택함
}
