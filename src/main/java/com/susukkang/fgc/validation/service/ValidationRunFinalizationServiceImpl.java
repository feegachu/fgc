package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.FinalizeChecklistConditionResponse;
import com.susukkang.fgc.validation.dto.FinalizeChecklistCounts;
import com.susukkang.fgc.validation.dto.FinalizeChecklistResponse;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** FGC-FUN-044-01 검증 실행 확정 체크리스트 조회 처리. */
@Service
@RequiredArgsConstructor
public class ValidationRunFinalizationServiceImpl implements ValidationRunFinalizationService {

    private final ValidationRunMapper validationRunMapper;

    @Override
    @Transactional(readOnly = true)
    public FinalizeChecklistResponse getChecklist(Long validationRunId) {
        return buildChecklist(requireChecklistCounts(validationRunId));
    }

    private FinalizeChecklistCounts requireChecklistCounts(Long validationRunId) {
        FinalizeChecklistCounts counts = validationRunMapper.findFinalizeChecklistCounts(validationRunId);
        if (counts == null) {
            throw notFound(validationRunId);
        }
        return counts;
    }

    private FinalizeChecklistResponse buildChecklist(FinalizeChecklistCounts counts) {
        Long id = counts.getValidationRunId();
        String month = YearMonth.from(counts.getValidationMonth()).toString();
        List<FinalizeChecklistConditionResponse> conditions = List.of(
                condition(1, "검증 실행 상태가 계산완료(COMPLETED)인가",
                        counts.getIncompleteRunCount(), "/validation-runs/" + id),
                condition(2, "원장 불균형(차변≠대변)이 0건인가",
                        counts.getJournalImbalanceCount(),
                        "/api/v1/journals/imbalances?validationRunId=" + id),
                condition(3, "심각도 긴급(CRITICAL) 미처리 예외가 0건인가",
                        counts.getUnresolvedCriticalExceptionCount(),
                        "/exceptions?validationRunId=" + id + "&severity=CRITICAL&status=OPEN"),
                condition(4, "정책 없음 · 정책 중복이 0건인가",
                        counts.getUnresolvedPolicyExceptionCount(),
                        "/exceptions?validationRunId=" + id
                                + "&types=POLICY_MISSING&types=POLICY_DUPLICATE&status=OPEN"),
                condition(5, "귀속합계 오류가 0건인가",
                        counts.getAttributionImbalanceCount(),
                        "/transactions?settlementMonth=" + month + "&attributionImbalanceOnly=true"),
                condition(6, "계약별 상세 합계 = 실행 요약 합계인가",
                        counts.getCapDetailMismatchCount(),
                        "/validation-runs/" + id)
        );
        return new FinalizeChecklistResponse(id,
                conditions.stream().allMatch(FinalizeChecklistConditionResponse::passed), conditions);
    }

    private FinalizeChecklistConditionResponse condition(int no, String label, long count, String linkUrl) {
        return new FinalizeChecklistConditionResponse(no, label, count == 0, count, linkUrl);
    }

    private FgcBusinessException notFound(Long validationRunId) {
        return new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", validationRunId));
    }
}
