package com.susukkang.fgc.common.exception;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 데이터베이스 제약조건 위반을 대응하는 FgcErrorCode로 변환한다.
 *
 * 등록되지 않은 제약조건은 반환하지 않으며,
 * 호출 측에서 공통 서버 오류로 처리한다.
 */
@Component
public class ConstraintErrorCodeResolver {

    private static final Map<String, FgcErrorCode> CONSTRAINT_MAPPING =
            createConstraintMapping();

    public Optional<FgcErrorCode> resolve(Throwable throwable) {
        String message = findMostSpecificMessage(throwable)
                .toLowerCase(Locale.ROOT);

        return CONSTRAINT_MAPPING.entrySet()
                .stream()
                .filter(entry -> message.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static Map<String, FgcErrorCode> createConstraintMapping() {
        Map<String, FgcErrorCode> mappings = new LinkedHashMap<>();

        mappings.put(
                "uq_commission_transaction_source",
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