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
import com.susukkang.fgc.validation.mapper.ValidationRunDetailMapper;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ValidationRunDetailServiceImpl implements ValidationRunDetailService {

    // ponytail: 페이징 없이 200행 캡 — 데모 계약 규모(수십 건)에 충분, 대상 폭증 시 페이징 추가
    private static final int TARGET_LIMIT = 200;

    private final ValidationRunDetailMapper validationRunDetailMapper;
    private final ValidationRunMapper validationRunMapper;

    @Override
    @Transactional(readOnly = true)
    public ValidationRunDetailResponse detail(Long validationRunId) {
        ValidationRunListRow header = validationRunDetailMapper.findHeaderById(validationRunId);
        if (header == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", validationRunId));
        }

        List<ValidationTargetItemResponse> targets =
                validationRunDetailMapper.findTargets(validationRunId, TARGET_LIMIT).stream()
                        .map(ValidationTargetItemResponse::from)
                        .toList();
        ValidationRunResultSummaryRow summary = validationRunDetailMapper.summarize(validationRunId);

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
        ValidationRunRow row = validationRunMapper.findById(validationRunId);
        if (row == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", validationRunId));
        }
        return ValidationRunProgressResponse.from(row);
    }
}
