package com.susukkang.fgc.exceptioncase.service;

import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionResponse;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionResponse;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionExceptionTarget;
import com.susukkang.fgc.exceptioncase.mapper.ExceptionCaseActionMapper;
import com.susukkang.fgc.journal.dto.JournalCorrectionResult;
import com.susukkang.fgc.journal.dto.JournalRepostCommand;
import com.susukkang.fgc.journal.dto.JournalRepostLineCommand;
import com.susukkang.fgc.journal.service.JournalCorrectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 설명 : 원장 정정 예외의 역분개·재기표와 종결을 한 트랜잭션으로 처리하는 서비스
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class JournalCorrectionExceptionActionService {

    private static final String JOURNAL_HEADER = "JOURNAL_HEADER";

    private final ExceptionCaseActionMapper exceptionCaseActionMapper;
    private final JournalCorrectionService journalCorrectionService;
    private final ExceptionCaseService exceptionCaseService;

    @Transactional
    public JournalCorrectionActionResponse correct(
            Long exceptionCaseId,
            JournalCorrectionActionRequest request,
            Long actionUserId,
            String actionUserLoginId
    ) {
        JournalCorrectionExceptionTarget target = exceptionCaseActionMapper
                .findJournalCorrectionTargetForUpdate(exceptionCaseId);
        if (target == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004,
                    "exceptionCaseId", Map.of("field", "exceptionCaseId"), null);
        }
        validateTarget(target);

        Long journalHeaderId = parseJournalHeaderId(target.sourceEntityId());
        JournalRepostCommand command = new JournalRepostCommand(
                journalHeaderId,
                request.reason(),
                request.evidenceRef(),
                actionUserId,
                request.journalDate(),
                request.description(),
                request.lines().stream()
                        .map(line -> new JournalRepostLineCommand(
                                line.originalLineNo(), line.accountCode(), line.debitAmount(),
                                line.creditAmount(), line.lineDescription()))
                        .toList());

        // 2026-08-20 yslee - 실제 원장 정정과 예외 종결을 동일 트랜잭션에 결합
        // 기존 코드: CORRECT 처리이력만 저장해도 예외가 RESOLVED가 되어 원장은 그대로 남음
        // 문제: 화면상 해결과 실제 원장 상태가 달라 감사 추적과 정정 결과가 불일치함
        // 개선: reverseAndRepost 성공 후에만 CORRECT 이력과 RESOLVED 전이를 저장하고 실패 시 전체 롤백
        JournalCorrectionResult correction = journalCorrectionService.reverseAndRepost(command);
        ExceptionActionResponse action = exceptionCaseService.actionAfterJournalCorrection(
                exceptionCaseId,
                new ExceptionActionRequest(
                        ExceptionActionType.CORRECT, request.reason(), request.evidenceRef()),
                actionUserId,
                actionUserLoginId);
        return JournalCorrectionActionResponse.from(action, correction);
    }

    private void validateTarget(JournalCorrectionExceptionTarget target) {
        if (!ExceptionType.JOURNAL_CORRECTION_REQUIRED.name().equals(target.exceptionType())
                || !JOURNAL_HEADER.equals(target.sourceEntityType())) {
            throw new FgcBusinessException(FgcErrorCode.EXCP_004,
                    Map.of("exceptionCaseId", target.exceptionCaseId()));
        }
        if (target.status() != ExceptionStatus.IN_REVIEW) {
            throw new FgcBusinessException(FgcErrorCode.EXCP_003,
                    Map.of("status", target.status().name(),
                            "actionType", ExceptionActionType.CORRECT.name()));
        }
    }

    private Long parseJournalHeaderId(String sourceEntityId) {
        try {
            return Long.valueOf(sourceEntityId);
        } catch (NumberFormatException | NullPointerException exception) {
            throw new FgcBusinessException(FgcErrorCode.EXCP_004,
                    Map.of("sourceEntityId", String.valueOf(sourceEntityId)));
        }
    }
}
