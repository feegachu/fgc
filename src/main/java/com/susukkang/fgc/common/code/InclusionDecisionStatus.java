package com.susukkang.fgc.common.code;

/**
 * 설명 : 1,200% 한도 산입 판단 상태
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
public enum InclusionDecisionStatus {
    INCLUDED,
    EXCLUDED,
    REVIEW_REQUIRED;

    // 화면정의서 TRAN-W02 "산입 판정" 필드(transaction_attribution.inclusion_status_snapshot을
    // 그대로 채움, docs/FGC_화면정의서_v2_0.md:736)와 용어집(:1751 "산입/제외 | 한도 계산에
    // 넣음/넣지 않음"), 1,200% 상세내역 표기(:986-1000)가 모두 "산입/제외/검토필요"를 쓴다 —
    // CapCheckDetailResponse 관련 테스트(CapCheckServiceImplTest, CapCheckControllerTest)도
    // 같은 값 도메인에 "산입"을 쓰고 있어 맞춘다(코드리뷰 반영, 이전 "포함"은 오표기였다).
    public String label() {
        return switch (this) {
            case INCLUDED -> "산입";
            case EXCLUDED -> "제외";
            case REVIEW_REQUIRED -> "검토필요";
        };
    }
}
