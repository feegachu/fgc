package com.susukkang.fgc.common.code;

import java.util.List;

/**
 * 설명 : exception_case 테이블에서 사용하는 예외 상태
 * 참고 : 화면정의서 v2.0의 예외 상태 대조표(:305-308),
 *        V1__baseline_v2_1_2.sql:1520 CHECK 제약과 값이 같아야 한다
 */
public enum ExceptionStatus {
    NEW,        // 신규
    IN_REVIEW,  // 검토중
    RESOLVED,   // 해결
    REJECTED;   // 오탐·반려

    /**
     * EXCP-W01 상태 select 와 대시보드 KPI 카드가 쓰는 화면 묶음 필터 "미처리(신규+검토중)".
     * exception_case.status 에는 없는 값이라 DB/API 로 그대로 흘려보내면
     * 어떤 행과도 매칭되지 않는다(#83).
     */
    public static final String OPEN_FILTER = "OPEN";

    /** OPEN 묶음이 뜻하는 미처리 상태들 — "미처리 = 신규 + 검토중"의 유일한 정의. */
    private static final List<ExceptionStatus> OPEN_STATUSES = List.of(NEW, IN_REVIEW);

    /**
     * 이 상태 문자열이 미처리(OPEN 묶음)인가.
     * FUN-034 등 "미해결이면 …" 판정이 각자 NEW/IN_REVIEW 를 나열하지 않게 한다.
     */
    public static boolean isOpen(String status) {
        return OPEN_STATUSES.stream().anyMatch(s -> s.name().equals(status));
    }

    /**
     * 화면 필터값 → DB 상태값 목록. 변환은 여기 한 곳에서만 한다(#83).
     *
     * <ul>
     *   <li>빈 값(전체) → 빈 목록(필터 없음). 명시적 빈 문자열만이다 —
     *       공백만 있는 값은 select 가 만들 수 없는 오타성 입력이라 미지원으로 취급한다</li>
     *   <li>{@code OPEN} → NEW + IN_REVIEW</li>
     *   <li>실제 상태 코드 → 그 코드 1개</li>
     *   <li>그 밖의 값 → {@link IllegalArgumentException}</li>
     * </ul>
     */
    public static List<ExceptionStatus> dbStatuses(String filter) {
        if (filter == null || filter.isEmpty()) {
            return List.of();
        }
        if (OPEN_FILTER.equals(filter)) {
            return OPEN_STATUSES;
        }
        return List.of(valueOf(filter));
    }
}
