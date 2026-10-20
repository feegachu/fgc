package com.susukkang.fgc.audit.view;

import com.susukkang.fgc.journal.domain.JournalType;

import java.util.Map;
import java.util.regex.Pattern;

/** 감사 화면의 표시 전용 매핑. 미등록 코드는 원문을 유지한다. */
public final class AuditDisplayLabels {
    private static final Map<String, String> ACTIONS = Map.ofEntries(
            Map.entry("LOGIN_SUCCESS", "로그인 성공"),
            Map.entry("LOGIN_FAIL", "로그인 실패"),
            Map.entry("LOGOUT", "로그아웃"),
            Map.entry("LOGIN_SUPERSEDED", "새 로그인으로 기존 로그인 무효화"),
            Map.entry("TOKEN_REFRESH", "인증 토큰 갱신"),
            Map.entry("REFRESH_REUSE_DETECTED", "갱신 토큰 재사용 탐지"),
            Map.entry("CONTRACT_CREATED", "계약 등록"),
            Map.entry("CONTRACT_UPDATED", "계약 수정"),
            Map.entry("CONTRACT_CAP_RECHECKED", "계약 한도 재검증"),
            Map.entry("CONTRACT_SCHEDULES_REGENERATED", "계약 스케줄 재생성"),
            Map.entry("PAYMENT_CREATED", "지급 등록"),
            Map.entry("PAYMENT_UPDATED", "지급 수정"),
            Map.entry("PAYMENT_CONFIRMED", "지급 확정"),
            Map.entry("PAYMENT_CANCELLED", "지급 취소"),
            Map.entry("SCHEDULE_CONFIRMED", "스케줄 확정"),
            Map.entry("ARBITRAGE_RECHECKED", "차익거래 재검증"),
            Map.entry("EXCEPTION_ACTION", "예외 조치"),
            Map.entry("JOURNAL_DRAFT_SAVED", "분개 초안 저장"),
            Map.entry("JOURNAL_CORRECTION_REQUESTED", "원장 정정 요청"),
            Map.entry("JOURNAL_REVERSED", "원분개 역분개"),
            Map.entry("JOURNAL_REVERSED_AND_REPOSTED", "원분개 역분개 및 재기표"),
            Map.entry("VALIDATION_RUN_STARTED", "검증 실행 시작"),
            Map.entry("VALIDATION_RUN_COMPLETED", "검증 실행 완료"),
            Map.entry("VALIDATION_RUN_STEP_ADVANCED", "검증 실행 단계 진행"),
            Map.entry("VALIDATION_RUN_FAILED", "검증 실행 실패"),
            Map.entry("VALIDATION_RUN_CREATION_FAILED", "검증 실행 생성 실패"),
            Map.entry("VALIDATION_RUN_RETRIED", "검증 실행 재시도"),
            Map.entry("VALIDATION_RUN_FINALIZED", "검증 실행 확정")
    );
    private static final Map<String, String> ENTITY_TYPES = Map.ofEntries(
            Map.entry("APP_USER", "사용자 계정"),
            Map.entry("CONTRACT", "보험계약"),
            Map.entry("COMMISSION_PAYMENT", "수수료 지급"),
            Map.entry("COMMISSION_TRANSACTION", "수수료 거래"),
            Map.entry("SCHEDULE", "지급 스케줄"),
            Map.entry("ARBITRAGE_CHECK", "차익거래 검증"),
            Map.entry("VALIDATION_RUN", "검증 실행"),
            Map.entry("BATCH_JOB_EXECUTION", "배치 작업 실행"),
            Map.entry("JOURNAL_HEADER", "회계전표"),
            Map.entry("EXCEPTION_CASE", "예외 건"),
            Map.entry("SCHEDULE_LINE", "스케줄행")
    );

    private static final Map<String, String> LOGIN_FAILURE_REASONS = Map.of(
            "BadCredentialsException", "아이디 또는 비밀번호 불일치",
            "UsernameNotFoundException", "사용자 계정 없음",
            "DisabledException", "사용 중지된 계정",
            "LockedException", "잠긴 계정",
            "AccountExpiredException", "계정 유효기간 만료",
            "CredentialsExpiredException", "비밀번호 유효기간 만료"
    );
    private static final Pattern STEP_REASON = Pattern.compile("MonthlyValidationJob advanced to step ([0-9]+)");
    private static final Pattern DRAFT_REASON = Pattern.compile(
            "journalType=([A-Z_]+),sourceEntityType=([A-Z_]+),sourceEntityId=([0-9]+|null),validationRunId=([0-9]+|null)");

    public String action(String code) {
        return label(code, ACTIONS);
    }

    public String entityType(String code) {
        return label(code, ENTITY_TYPES);
    }

    public String actionWithCode(String code) {
        return withCode(code, action(code));
    }

    public String entityTypeWithCode(String code) {
        return withCode(code, entityType(code));
    }

    /** 자동 생성한 사유의 알려진 형식만 번역하며 저장 원문은 변경하지 않는다. */
    public String reason(String actionCode, String reason) {
        if (reason == null || reason.isEmpty()) return "-";
        if ("VALIDATION_RUN_STARTED".equals(actionCode) && "MonthlyValidationJob started".equals(reason))
            return "월 통합검증 시작";
        if ("VALIDATION_RUN_COMPLETED".equals(actionCode) && "MonthlyValidationJob completed".equals(reason))
            return "월 통합검증 완료";
        if ("VALIDATION_RUN_RETRIED".equals(actionCode)
                && "FAILED validation_run retried (status reverted to RUNNING)".equals(reason))
            return "실패한 월 통합검증 재시도 · 실행 중으로 전환";
        if ("VALIDATION_RUN_STEP_ADVANCED".equals(actionCode)) {
            var step = STEP_REASON.matcher(reason);
            if (step.matches()) return "월 통합검증 " + step.group(1) + "단계 진행";
        }
        if ("LOGIN_FAIL".equals(actionCode)) return LOGIN_FAILURE_REASONS.getOrDefault(reason, reason);
        if ("JOURNAL_DRAFT_SAVED".equals(actionCode)) {
            var draft = DRAFT_REASON.matcher(reason);
            if (draft.matches() && ENTITY_TYPES.containsKey(draft.group(2))) {
                JournalType type;
                try {
                    type = JournalType.valueOf(draft.group(1));
                } catch (IllegalArgumentException unknownType) {
                    return reason;
                }
                String sourceId = "null".equals(draft.group(3)) ? " (식별자 없음)" : " #" + draft.group(3);
                String run = "null".equals(draft.group(4)) ? "검증 실행 없음" : "검증 실행 #" + draft.group(4);
                return type.label() + " 분개 초안 저장 · 원천: " + ENTITY_TYPES.get(draft.group(2)) + sourceId + " · " + run;
            }
        }
        return reason;
    }

    private static String label(String code, Map<String, String> labels) {
        return code == null || code.isEmpty() ? "-" : labels.getOrDefault(code, code);
    }

    private static String withCode(String code, String label) {
        return code != null && !code.isEmpty() && !label.equals(code) ? label + " (" + code + ")" : label;
    }
}
