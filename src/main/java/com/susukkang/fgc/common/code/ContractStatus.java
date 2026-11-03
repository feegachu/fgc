package com.susukkang.fgc.common.code;

import java.util.EnumSet;
import java.util.Set;

/**
 * 설명 : ContractStatus , 계약 상태 컬럼
 * APPLIED','ACTIVE','UNPAID','LAPSED','REVIVED','CANCELLED','TERMINATED','MATURED 의 상태가 존재
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
public enum ContractStatus {
    APPLIED,
    ACTIVE,
    UNPAID,
    LAPSED,
    REVIVED,
    CANCELLED,
    TERMINATED,
    MATURED;

    /** 화면과 API가 동일한 계약 상태 한글 라벨을 사용하도록 enum에서 관리한다. */
    public String label() {
        return switch (this) {
            case APPLIED -> "청약";
            case ACTIVE -> "정상";
            case UNPAID -> "미납";
            case LAPSED -> "실효";
            case REVIVED -> "부활";
            case CANCELLED -> "청약철회";
            case TERMINATED -> "해지";
            case MATURED -> "만기";
        };
    }

    /**
     * 계약 수정(IF-API-19)으로 바꿀 수 있는 다음 상태. 운영정책서 제13조의2 1항(D-03)의 허용 전이 15개이며
     * 서버 전이 검사와 화면 선택지가 이 표 하나를 쓴다. 끝 상태(청약철회·해지·만기)는 빈 집합이다.
     */
    public Set<ContractStatus> nextStatuses() {
        return switch (this) {
            case APPLIED -> EnumSet.of(ACTIVE, CANCELLED);
            case ACTIVE -> EnumSet.of(UNPAID, TERMINATED, MATURED, CANCELLED);
            case UNPAID -> EnumSet.of(ACTIVE, LAPSED, TERMINATED, MATURED, CANCELLED);
            case LAPSED -> EnumSet.of(REVIVED);
            case REVIVED -> EnumSet.of(UNPAID, TERMINATED, MATURED);
            case CANCELLED, TERMINATED, MATURED -> EnumSet.noneOf(ContractStatus.class);
        };
    }
}
