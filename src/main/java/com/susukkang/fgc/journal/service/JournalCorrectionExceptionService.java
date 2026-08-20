package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionInsertCommand;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRequest;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionResponse;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRow;
import com.susukkang.fgc.journal.dto.JournalCorrectionHeaderRow;
import com.susukkang.fgc.journal.mapper.JournalCorrectionExceptionMapper;
import com.susukkang.fgc.journal.mapper.JournalCorrectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 설명 : 원분개와 연결된 원장 정정 예외를 중복 없이 생성하는 서비스
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class JournalCorrectionExceptionService {

    private static final String EXCEPTION_KEY_PREFIX = "JOURNAL_HEADER:";

    private final JournalCorrectionMapper journalCorrectionMapper;
    private final JournalCorrectionExceptionMapper correctionExceptionMapper;
    private final AuditLogService auditLogService;

    @Transactional
    public JournalCorrectionExceptionResponse createOrGet(
            Long journalHeaderId,
            JournalCorrectionExceptionRequest request,
            Long requestedBy
    ) {
        validateRequest(journalHeaderId, request, requestedBy);
        JournalCorrectionHeaderRow original =
                journalCorrectionMapper.findHeaderForUpdate(journalHeaderId);
        validateOriginal(original, journalHeaderId);

        String reason = request.reason().trim();
        String evidenceRef = normalizeOptional(request.evidenceRef());

        // 2026-08-20 yslee - 미종결 원장 정정 예외만 멱등 재사용하도록 생명주기 키 분리
        // 기존 코드: 원분개 ID 하나를 영구 exception_key로 사용해 REJECTED 이후에도 종결 건을 반환
        // 문제: 종결 예외에는 재처리 폼이 없어 같은 원분개의 정정을 다시 요청할 수 없음
        // 개선: 원분개 잠금 안에서 활성 건을 먼저 조회하고 종결 이력 다음 요청은 새 순번 키로 생성
        JournalCorrectionExceptionRow active = correctionExceptionMapper.findActiveBySource(
                journalHeaderId, original.getPolicyVersionId());
        if (active != null) {
            return JournalCorrectionExceptionResponse.from(active, false);
        }
        int requestNo = correctionExceptionMapper.countBySource(
                journalHeaderId, original.getPolicyVersionId()) + 1;
        String exceptionKey = buildExceptionKey(
                journalHeaderId, original.getPolicyVersionId(), requestNo);
        JournalCorrectionExceptionInsertCommand command =
                JournalCorrectionExceptionInsertCommand.builder()
                        .exceptionKey(exceptionKey)
                        .validationRunId(original.getValidationRunId())
                        .validationMonth(original.getJournalDate().withDayOfMonth(1))
                        .contractId(original.getContractId())
                        .policyVersionId(original.getPolicyVersionId())
                        .journalHeaderId(journalHeaderId)
                        .title("원장 #" + journalHeaderId + " 정정 필요")
                        .description(reason)
                        .reason(reason)
                        .evidenceRef(evidenceRef)
                        .requestedBy(requestedBy)
                        .build();

        boolean created = correctionExceptionMapper.insertCase(command) == 1;
        JournalCorrectionExceptionRow row =
                correctionExceptionMapper.findByExceptionKey(exceptionKey);
        if (row == null) {
            throw new IllegalStateException("원장 정정 예외 조회에 실패했습니다.");
        }

        if (created) {
            if (correctionExceptionMapper.insertInitialAction(command) != 1) {
                throw new IllegalStateException("원장 정정 요청 이력 저장에 실패했습니다.");
            }
            auditLogService.record(AuditLogService.AuditEvent.builder()
                    .actionCode("JOURNAL_CORRECTION_REQUESTED")
                    .entityType("EXCEPTION_CASE")
                    .entityId(String.valueOf(row.exceptionCaseId()))
                    .userId(requestedBy)
                    .after(new CorrectionExceptionAuditValue(
                            journalHeaderId, row.status(), evidenceRef))
                    .reason(reason)
                    .policyVersionId(original.getPolicyVersionId())
                    .build());
        }
        return JournalCorrectionExceptionResponse.from(row, created);
    }

    private String buildExceptionKey(Long journalHeaderId,
                                     Long policyVersionId,
                                     int requestNo) {
        return EXCEPTION_KEY_PREFIX + journalHeaderId
                + ":JOURNAL_CORRECTION_REQUIRED:POLICY_VERSION:"
                + (policyVersionId == null ? "NONE" : policyVersionId)
                + ":REQUEST:" + requestNo;
    }

    private void validateRequest(Long journalHeaderId,
                                 JournalCorrectionExceptionRequest request,
                                 Long requestedBy) {
        if (journalHeaderId == null || requestedBy == null || request == null
                || request.reason() == null || request.reason().isBlank()
                || request.reason().length() > 1000
                || (request.evidenceRef() != null && request.evidenceRef().length() > 500)) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002,
                    "reason", Map.of("field", "reason"), null);
        }
    }

    private void validateOriginal(JournalCorrectionHeaderRow original, Long journalHeaderId) {
        if (original == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004,
                    Map.of("resource", "journal", "id", journalHeaderId));
        }
        if ("FINALIZED".equals(original.getValidationRunStatus())) {
            throw new FgcBusinessException(FgcErrorCode.VRUN_003,
                    Map.of("validationRunId", original.getValidationRunId()));
        }
        if (!"POSTED".equals(original.getStatus())
                || JournalType.REVERSAL.name().equals(original.getJournalType())) {
            throw new FgcBusinessException(FgcErrorCode.LEDG_004,
                    Map.of("journalHeaderId", journalHeaderId));
        }
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record CorrectionExceptionAuditValue(
            Long journalHeaderId,
            ExceptionStatus status,
            String evidenceRef
    ) {
    }
}
