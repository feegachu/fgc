package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.ValidationRunDetailResponse;
import com.susukkang.fgc.validation.dto.ValidationRunItemResponse;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunProgressResponse;
import com.susukkang.fgc.validation.dto.ValidationRunResultSummaryRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.dto.ValidationTargetItemResponse;
import com.susukkang.fgc.validation.entity.ValidationRun;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import com.susukkang.fgc.validation.repository.ValidationTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ValidationRunDetailServiceImpl implements ValidationRunDetailService {

    // ponytail: 페이징 없이 200행 캡 — 데모 계약 규모(수십 건)에 충분, 대상 폭증 시 페이징 추가
    private static final int TARGET_LIMIT = 200;

    private final ValidationRunRepository validationRunRepository;
    private final ValidationTargetRepository validationTargetRepository;

    @Override
    @Transactional(readOnly = true)
    public ValidationRunDetailResponse detail(Long validationRunId) {
        ValidationRunListRow header = validationRunRepository.findHeaderById(validationRunId)
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", validationRunId)));

        List<ValidationTargetItemResponse> targets =
                validationTargetRepository.findTargets(validationRunId, PageRequest.of(0, TARGET_LIMIT)).stream()
                        .map(ValidationTargetItemResponse::from)
                        .toList();
        ValidationRunResultSummaryRow summary = validationRunRepository.summarize(validationRunId)
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", validationRunId)));

        return new ValidationRunDetailResponse(
                ValidationRunItemResponse.from(header),
                targets,
                new ValidationRunDetailResponse.TargetSummary(
                        summary.getTargetSelectedCount(),
                        summary.getTargetExcludedCount(),
                        summary.getTargetReviewRequiredCount()),
                new ValidationRunDetailResponse.CapSummary(
                        summary.getCapCheckedCount(),
                        summary.getCapViolationCount(),
                        summary.getCapWarningCount(),
                        summary.getCapReviewRequiredCount()),
                new ValidationRunDetailResponse.ArbitrageSummary(
                        summary.getArbitrageCheckedCount(),
                        summary.getArbitrageCandidateCount(),
                        summary.getArbitrageReviewRequiredCount()),
                new ValidationRunDetailResponse.LedgerSummary(
                        summary.getJournalCount(),
                        summary.getJournalImbalanceCount()),
                new ValidationRunDetailResponse.ReconciliationSummary(
                        summary.getReconciliationResultCount(),
                        summary.getReconciliationMismatchCount(),
                        summary.getReconciliationMatchedCount(),
                        summary.getReconciliationMismatchedCount(),
                        summary.getReconciliationUnmatchedCount(),
                        summary.getReconciliationDifferenceAmountTotal()),
                new ValidationRunDetailResponse.ExceptionSummary(
                        summary.getExceptionDetectedCount(),
                        summary.getExceptionNewCount(),
                        summary.getExceptionRecurringCount(),
                        summary.getExceptionReopenedCount(),
                        summary.getExceptionNotDetectedCount(),
                        summary.getExceptionOpenWorkItemCount()));
    }

    @Override
    @Transactional(readOnly = true)
    public ValidationRunProgressResponse progress(Long validationRunId) {
        ValidationRun run = validationRunRepository.findById(validationRunId)
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", validationRunId)));
        return ValidationRunProgressResponse.from(toRow(run));
    }

    private ValidationRunRow toRow(ValidationRun run) {
        ValidationRunRow row = new ValidationRunRow();
        row.setValidationRunId(run.getValidationRunId());
        row.setValidationMonth(run.getValidationMonth());
        row.setRunNo(run.getRunNo());
        row.setRunType(run.getRunType());
        row.setStatus(run.getStatus().name());
        row.setCurrentStep(run.getCurrentStep());
        row.setStartedAt(run.getStartedAt());
        row.setCompletedAt(run.getCompletedAt());
        row.setFinalizedAt(run.getFinalizedAt());
        row.setTriggeredBy(run.getTriggeredBy());
        row.setFinalizedBy(run.getFinalizedBy());
        row.setFailureMessage(run.getFailureMessage());
        row.setCreatedAt(run.getCreatedAt());
        return row;
    }
}
