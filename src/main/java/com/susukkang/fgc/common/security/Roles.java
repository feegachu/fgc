package com.susukkang.fgc.common.security;

/**
 * FUN-002 역할 코드와 역할 조합을 한 곳에 모은다 (이슈 #82).
 *
 * 이전에는 "SETTLEMENT"·"SYSTEM_ADMIN" 같은 리터럴이 컨트롤러 11곳 + ShellAdvice +
 * sidebar.html 에 흩어져 있었다. 여기 상수를 참조하게 해서 역할 조합을 바꿀 때 한 곳만 고치면
 * 되게 한다.
 *
 * SpEL 상수가 @PreAuthorize 애노테이션에 그대로 쓰이는 이유
 *  애노테이션 값은 컴파일타임 상수여야 한다. static final String 리터럴끼리의 "+" 결합은
 *  자바 컴파일러가 컴파일타임에 접어(constant fold) 상수로 취급하므로 애노테이션에 쓸 수 있다.
 *
 * 지금 실제로 쓰이는 역할 조합만 정의한다. 새 조합(예: EXCP-W01의 SETTLEMENT·GA_ADMIN, 아직
 * 미구현인 역분개·검증실행확정)은 그 엔드포인트가 실제로 생길 때 추가한다.
 */
public final class Roles {

    public static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    public static final String GA_ADMIN = "GA_ADMIN";
    public static final String SETTLEMENT = "SETTLEMENT";
    public static final String COMPLIANCE = "COMPLIANCE";

    /** 계약·지급·스케줄·검증실행 생성 등 "처리" 화면 공통 역할. 화면정의서 v2.0 §4-1/각 화면 권한 줄. */
    public static final String CAN_PROCESS =
            "hasAnyRole('" + SETTLEMENT + "','" + SYSTEM_ADMIN + "')";

    /** AUDT-W01 감사로그 조회. 화면정의서 v2.0 :1503,:1530. */
    public static final String CAN_VIEW_AUDIT_LOG =
            "hasAnyRole('" + COMPLIANCE + "','" + SYSTEM_ADMIN + "')";

    /**
     * "전체 조회" API용(기준정보 등). 사실상 authenticated()와 같지만, 역할 4종을 명시해
     * 새 역할이 추가될 때 조회 범위를 다시 판단하도록 강제한다.
     */
    public static final String ANY_ROLE = "hasAnyRole('" + SYSTEM_ADMIN + "','" + GA_ADMIN
            + "','" + SETTLEMENT + "','" + COMPLIANCE + "')";

    /**
     * SecurityConfig 의 URL 단위 굵은 규칙에 쓴다. COMPLIANCE 는 역할 정의(§4-1)상 "조회만"이라
     * 상태를 바꾸는 요청(POST/PUT/DELETE/PATCH)에서는 화면·API 종류를 가리지 않고 항상 배제된다.
     */
    public static final String[] NON_COMPLIANCE = {SYSTEM_ADMIN, GA_ADMIN, SETTLEMENT};

    private Roles() {
    }
}
