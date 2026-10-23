package com.susukkang.fgc.common.security;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.jpa.JPAExpressions;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.contract.entity.QContractManagerAssignment;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** FUN-085: 조회 서비스·다운로드·AI 도구가 공유하는 객체 범위. 요청의 agentId를 신뢰하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentDataScopeService {
    private final EntityManager entityManager;
    private final FgcUserDetailsService userDetailsService;

    /** 내부 업무·배치는 기존 문맥을 유지한다. AGENT는 세션도 DB의 최신 연결로 검사한다. */
    public Long currentAgentId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(authority -> authority.getAuthority().equals("ROLE_" + Roles.AGENT))) return null;
        if (!(authentication.getPrincipal() instanceof FgcUserDetails)) denied();
        FgcUserDetails current = (FgcUserDetails) userDetailsService.loadUserByUsername(authentication.getName());
        if (!Roles.AGENT.equals(current.getRoleCode()) || current.getAgentId() == null) denied();
        try {
            new AccountStatusUserDetailsChecker().check(current);
        } catch (AuthenticationException exception) {
            throw new FgcBusinessException(FgcErrorCode.AUTH_002);
        }
        return current.getAgentId();
    }

    /** 목록·count·CSV에 같은 predicate. 날짜 양끝은 담당 이력의 유효기간에 포함한다. */
    public BooleanExpression contractPredicate(NumberPath<Long> contractId, NumberPath<Long> solicitorId) {
        Long agentId = currentAgentId();
        if (agentId == null) return null;
        LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
        var assignment = new QContractManagerAssignment("scopeMaintenance");
        return solicitorId.eq(agentId).or(JPAExpressions.selectOne().from(assignment)
                .where(assignment.contractId.eq(contractId), assignment.agentId.eq(agentId),
                        assignment.assignmentType.eq("MAINTENANCE_MANAGER"), assignment.effectiveFrom.loe(today),
                        assignment.effectiveTo.isNull().or(assignment.effectiveTo.goe(today))).exists());
    }

    public void requireContract(Long contractId) {
        check("""
                SELECT count(*) FROM fgc.insurance_contract c WHERE c.contract_id = :id
                AND (c.agent_id = :agentId OR EXISTS (
                    SELECT 1 FROM fgc.contract_manager_assignment a
                    WHERE a.contract_id = c.contract_id AND a.agent_id = :agentId
                    AND a.assignment_type = 'MAINTENANCE_MANAGER'
                    AND a.effective_from <= :today AND (a.effective_to IS NULL OR a.effective_to >= :today)))
                """, contractId, true);
    }

    public void requirePayment(Long paymentId) {
        check("SELECT count(*) FROM fgc.commission_transaction WHERE commission_transaction_id = :id"
                + " AND recipient_agent_id = :agentId", paymentId, false);
    }

    /** 환수는 기지급 귀속행의 실제 수령인으로 판정한다. */
    public void requireClawback(Long caseId) {
        requireAuthenticated();
        check("""
                SELECT count(*) FROM fgc.clawback_line l
                JOIN fgc.transaction_attribution a ON a.transaction_attribution_id = l.transaction_attribution_id
                JOIN fgc.commission_transaction t ON t.commission_transaction_id = a.commission_transaction_id
                WHERE l.clawback_case_id = :id AND t.recipient_agent_id = :agentId
                """, caseId, false);
    }

    public void requireInquiry(Long inquiryId) {
        requireAuthenticated();
        check("SELECT count(*) FROM fgc.inquiry WHERE inquiry_id = :id AND agent_id = :agentId"
                + " AND created_by = :userId", inquiryId, false);
    }

    public void requireComparison(Long explanationId) {
        requireAuthenticated();
        check("SELECT count(*) FROM fgc.compare_explanation WHERE compare_explanation_id = :id"
                + " AND agent_id = :agentId", explanationId, false);
    }

    /** 문서번호뿐 아니라 요청한 PDF 판의 생성자/수신자를 확인한다. */
    public void requireDocument(String documentNo, int version) {
        requireAuthenticated();
        Long agentId = currentAgentId();
        if (agentId == null) return;
        var query = entityManager.createNativeQuery("""
                SELECT count(*) FROM fgc.generated_document d
                WHERE d.document_no = :documentNo AND d.version_no = :version AND (
                  (d.document_kind = 'COMPARE_CONFIRMATION' AND d.created_by = :userId)
                  OR (d.document_kind = 'CLAWBACK_NOTICE' AND EXISTS (
                    SELECT 1 FROM fgc.clawback_notice_delivery n
                    WHERE n.generated_document_id = d.generated_document_id AND n.recipient_agent_id = :agentId)))
                """);
        query.setParameter("documentNo", documentNo).setParameter("version", version)
                .setParameter("userId", userId()).setParameter("agentId", agentId);
        if (((Number) query.getSingleResult()).longValue() == 0) denied();
    }

    /** 문의·AI 도구는 조회/저장 전에 호출한다. 대상 종류는 고정 목록으로 해석한다. */
    public void requireTarget(String targetType, Long targetId) {
        requireAuthenticated();
        if (currentAgentId() == null) return;
        switch (targetType == null ? "" : targetType) {
            case "CONTRACT" -> requireContract(targetId);
            case "TRANSACTION" -> requirePayment(targetId);
            case "CLAWBACK" -> requireClawback(targetId);
            default -> denied();
        }
    }

    private void check(String sql, Long id, boolean withDate) {
        Long agentId = currentAgentId();
        if (agentId == null) return;
        if (id == null) denied();
        var query = entityManager.createNativeQuery(sql).setParameter("id", id).setParameter("agentId", agentId);
        if (withDate) query.setParameter("today", LocalDate.now(DateUtil.SEOUL_ZONE));
        if (sql.contains(":userId")) query.setParameter("userId", userId());
        if (((Number) query.getSingleResult()).longValue() == 0) denied();
    }

    private Long userId() {
        var principal = (FgcUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal.getUserId() == null) denied();
        return principal.getUserId();
    }

    /** AI/문서 도구에서 인증 문맥이 유실된 경우 내부 전체 범위로 해석하지 않는다. */
    private void requireAuthenticated() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            throw new FgcBusinessException(FgcErrorCode.AUTH_002);
        }
    }

    private static void denied() {
        throw new FgcBusinessException(FgcErrorCode.AUTH_005);
    }
}
