package com.susukkang.fgc.clawback.service;

import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateResponse;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateSearchCondition;
import com.susukkang.fgc.clawback.repository.ClawbackCandidateQueryRepository;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateResponse;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** FUN-054 검색 검증과 읽기 트랜잭션. count와 목록도 동일한 DB snapshot에서 읽는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ClawbackCandidateQueryService {
    private final ClawbackCandidateQueryRepository repository;

    /** 기간·양수 ID·페이지·정렬 허용값을 검증하고 같은 snapshot의 목록과 전체 건수를 반환한다. */
    public PageResponse<ClawbackCandidateResponse> search(ClawbackCandidateSearchCondition condition, int page, int size) {
        if (page < 1 || (long) (page - 1) * size > Integer.MAX_VALUE) throw invalid("page");
        if (size < 1 || size > 100) throw invalid("size");
        if (condition.getDateFrom() != null && condition.getDateTo() != null
                && condition.getDateFrom().isAfter(condition.getDateTo())) throw invalid("dateTo");
        if (condition.getContractNo() != null && condition.getContractNo().trim().length() > 80) throw invalid("contractNo");
        positive(condition.getInsurerId(), "insurerId");
        positive(condition.getProductOfferingId(), "productOfferingId");
        positive(condition.getAgentId(), "agentId");
        ListSort sort = ListSort.parse(condition.getSort(), ClawbackCandidateQueryRepository.SORTABLE.keySet(),
                ClawbackCandidateQueryRepository.DEFAULT_SORT);
        var result = repository.search(condition, sort, page, size);
        boolean canProcess = canProcess();
        return PageResponse.of(result.items().stream().map(ClawbackCandidateDetailResponse::candidate)
                        .map(candidate -> forRole(candidate, canProcess)).toList(),
                page, size, result.total(), sort.value());
    }

    /** 내부 사용자가 지정한 후보의 실제 근거를 읽으며 잘못된 ID와 없는 ID를 구분한다. */
    public ClawbackCandidateDetailResponse detail(long candidateId) {
        if (candidateId < 1) throw invalid("candidateId");
        ClawbackCandidateDetailResponse detail = repository.findById(candidateId).orElseThrow(() ->
                new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", candidateId)));
        return new ClawbackCandidateDetailResponse(forRole(detail.candidate(), canProcess()), detail.sourceRefs(),
                detail.paymentAttributions(), detail.scheduleLines(), detail.policyCandidates(), detail.effects(),
                detail.elapsedMonths(), detail.latestCalculation());
    }

    /** SQL은 근거로만 동작을 판정한다. 계산 권한(Roles.CAN_CALCULATE_CLAWBACK)이 없으면 동작을 비운다. */
    private static ClawbackCandidateResponse forRole(ClawbackCandidateResponse c, boolean canProcess) {
        if (canProcess || c.allowedActions().isEmpty()) return c;
        return new ClawbackCandidateResponse(c.candidateId(), c.contractId(), c.contractNo(), c.insurerId(),
                c.insurerName(), c.productOfferingId(), c.productName(), c.agentId(), c.agentName(), c.triggerType(),
                c.sourceId(), c.eventDate(), c.dateBasisLabel(), c.contractDate(), c.sourceVersion(),
                c.calculationStatus(), c.reviewReasons(), c.paidAmount(), c.unpaidAmount(), c.policyVersionId(),
                c.clawbackCaseId(), c.calculatedAmount(), List.of());
    }

    private static boolean canProcess() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> PROCESS_AUTHORITIES.contains(a.getAuthority()));
    }

    private static final Set<String> PROCESS_AUTHORITIES =
            Set.of("ROLE_" + Roles.SETTLEMENT, "ROLE_" + Roles.GA_ADMIN, "ROLE_" + Roles.SYSTEM_ADMIN);

    private static void positive(Long value, String field) {
        if (value != null && value < 1) throw invalid(field);
    }

    private static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }
}
