package com.susukkang.fgc.common.exception;

import org.postgresql.util.PSQLException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 설명 : 데이터베이스 제약조건 위반을 대응하는 FgcErrorCode로 변환
 *
 * @author yslee
 * @since 2026-08-07
 * @version 1.2
 * 등록되지 않은 제약조건은 반환하지 않으며,
 * 호출 측에서 공통 서버 오류로 처리한다.
 *
 * ── 왜 constraint 이름을 우선 쓰는가 ─────────────────────────────────────────
 * uq_validation_run과 uq_validation_run_active_month처럼 한쪽이 다른 쪽 이름을
 * 접두어로 포함하는 제약이 늘어나면서, 메시지 문자열 부분일치(message.contains)만으로는
 * 어느 제약이 실제로 위반됐는지 안전하게 구분할 수 없어졌다. PostgreSQL 예외
 * (PSQLException)는 위반된 제약의 정확한 이름을 구조화된 필드로 들고 있으므로, 그게
 * 있으면 그것부터 정확히 매칭하고, 없을 때만(예: 이 클래스를 순수 단위테스트에서
 * 합성 예외로 검증할 때) 기존 부분일치로 대체한다.
 *
 **/
@Component
public class ConstraintErrorCodeResolver {

    private static final Map<String, FgcErrorCode> CONSTRAINT_MAPPING =
            createConstraintMapping();

    public Optional<FgcErrorCode> resolve(Throwable throwable) {
        Optional<String> constraintName = extractConstraintName(throwable);
        if (constraintName.isPresent()) {
            FgcErrorCode exact = CONSTRAINT_MAPPING.get(constraintName.get());
            if (exact != null) {
                return Optional.of(exact);
            }
        }

        String message = findMostSpecificMessage(throwable)
                .toLowerCase(Locale.ROOT);

        return CONSTRAINT_MAPPING.entrySet()
                .stream()
                .filter(entry -> message.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /**
     * 원인 체인에서 PSQLException을 찾아 위반된 제약의 정확한 이름을 돌려준다.
     * PSQLException이 없거나(다른 DB, mock 예외 등) constraint 필드가 비어 있으면 empty.
     */
    public Optional<String> extractConstraintName(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof PSQLException psqlException
                    && psqlException.getServerErrorMessage() != null) {
                String constraint = psqlException.getServerErrorMessage().getConstraint();
                if (constraint != null) {
                    return Optional.of(constraint);
                }
            }
            current = current.getCause();
        }

        return Optional.empty();
    }

    private static Map<String, FgcErrorCode> createConstraintMapping() {
        Map<String, FgcErrorCode> mappings = new LinkedHashMap<>();

        mappings.put(
                "uq_commission_transaction_source",
                FgcErrorCode.TRAN_001
        );
        // 2026-08-11 yslee - 지급 확정 멱등키 중복을 거래 중복 업무 오류로 변환
        // 기존 코드: 지급 건 원천키와 자연키 중복 제약만 FGC-TRAN-001로 변환
        // 문제: 서로 다른 지급 건이 같은 Idempotency-Key를 사용하면 공통 500 오류로 노출
        // 개선: 확정 멱등키 고유 제약도 FGC-TRAN-001·409로 일관되게 응답
        mappings.put(
                "uq_commission_transaction_confirm_idempotency",
                FgcErrorCode.TRAN_001
        );
        mappings.put(
                "uq_contract_no",
                FgcErrorCode.CONT_001
        );
        mappings.put(
                "trg_schedule_header_status",
                FgcErrorCode.SCHE_001
        );
        mappings.put(
                "uq_reconciliation_result",
                FgcErrorCode.RECO_001
        );
        // 활성 MONTHLY 중복(진짜 업무 규칙 위반)과 run_no 채번 충돌(단순 경합)을 구분해야
        // ValidationRunCreateServiceImpl이 후자만 재시도할 수 있다 — 값 자체는 둘 다
        // VRUN_001·409로 같지만, 이름을 정확히 등록해 두 제약 사이 접두어 겹침으로
        // 오매칭되지 않게 한다.
        mappings.put(
                "uq_validation_run_active_month",
                FgcErrorCode.VRUN_001
        );
        mappings.put(
                "uq_validation_run",
                FgcErrorCode.VRUN_001
        );

        return Map.copyOf(mappings);
    }

    private String findMostSpecificMessage(Throwable throwable) {
        Throwable current = throwable;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        return current.getMessage() == null
                ? ""
                : current.getMessage();
    }
}
