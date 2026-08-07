package com.susukkang.fgc.common.exception;

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
        // 2026-08-07 yslee - 지급 건 귀속 자연키 중복을 업무 오류로 변환
        // 기존 코드: 원천 업무키 중복만 지급 건 중복 오류로 처리
        // 문제: 계약·설계사·항목·귀속월·순번 중복 제약 위반이 공통 서버 오류로 노출됨
        // 개선: 귀속 자연키 제약 위반도 FGC-TRAN-001로 일관되게 응답
        mappings.put(
                "uq_commission_payment_natural",
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
