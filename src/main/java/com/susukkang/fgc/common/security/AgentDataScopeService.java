package com.susukkang.fgc.common.security;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.NumberPath;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FUN-085: 조회 서비스·다운로드·AI 도구가 공유하는 객체 범위. 요청의 agentId를 신뢰하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentDataScopeService {
    private final EntityManager entityManager;
    private final FgcUserDetailsService userDetailsService;

    /**
     * 귀속행(별칭 ta)이 :agentId 설계사 몫인지 판정하는 SQL. GA_TO_FC의 ta.agent_id는 수취인이다(V36).
     * FC는 자기가 모집한 계약의 행만 본인 몫이다. 관리자 직급은 조직 계약의 관리자수수료를 받으므로 자기 수취 행 전부가
     * 본인 몫이다(지급 등록 검증 CommissionPaymentServiceImpl.requireManagerOfContractOrganization과 같은 기준).
     */
    public static String ownAttribution(String ta) {
        return """
                (%1$s.agent_id = :agentId AND (%1$s.contract_id IS NULL
                    OR EXISTS (SELECT 1 FROM fgc.insurance_contract own_contract
                                WHERE own_contract.contract_id = %1$s.contract_id AND own_contract.agent_id = :agentId)
                    OR EXISTS (SELECT 1 FROM fgc.agent own_agent
                                WHERE own_agent.agent_id = :agentId AND own_agent.rank_code <> 'FC')))
                """.formatted(ta);
    }

    /** 지급 건(별칭 ct)을 :agentId가 볼 수 있는 조건. 귀속행이 있으면 본인 몫이 하나는 있어야 하고, 없으면 본인 수취 건 전체가 본인 것이다. */
    public static final String OWN_PAYMENT = """
            ct.recipient_agent_id = :agentId AND (EXISTS (SELECT 1 FROM fgc.transaction_attribution own_ta
                    WHERE own_ta.commission_transaction_id = ct.commission_transaction_id AND %s)
                OR NOT EXISTS (SELECT 1 FROM fgc.transaction_attribution any_ta
                    WHERE any_ta.commission_transaction_id = ct.commission_transaction_id))
            """.formatted(ownAttribution("own_ta"));

    /** 지급 건(별칭 ct)에 :agentId 몫이 아닌 귀속행이 섞여 있는지. 이때는 헤더 총액과 전체 근거를 AGENT에게 내주지 않는다. */
    public static final String MIXED_PAYMENT = """
            EXISTS (SELECT 1 FROM fgc.transaction_attribution other_ta
                     WHERE other_ta.commission_transaction_id = ct.commission_transaction_id AND %s IS NOT TRUE)
            """.formatted(ownAttribution("other_ta"));

    /** 지급 건(paymentId 표현식)에 남의 몫이 섞였으면 :agentId의 귀속 합계를 돌려주는 SQL. 섞이지 않았으면 행이 없다. */
    public static String ownAmountIfMixed(String paymentId) {
        return """
                SELECT (SELECT COALESCE(SUM(ta.attributed_amount), 0) FROM fgc.transaction_attribution ta
                         WHERE ta.commission_transaction_id = ct.commission_transaction_id AND %s) AS amount
                  FROM fgc.commission_transaction ct
                 WHERE ct.commission_transaction_id = %s AND %s
                """.formatted(ownAttribution("ta"), paymentId, MIXED_PAYMENT);
    }

    /**
     * 내부 역할은 null(전체 범위), AGENT는 세션도 DB의 최신 연결로 검사한다. 인증 문맥이 없으면 어느 쪽으로도 해석하지 않는다.
     * 비동기나 LLM 스레드에서 문맥이 사라져도 전체 범위가 되지 않게 한다. 배치와 예약 작업은 이 서비스를 거치는 조회를 쓰지 않는다.
     */
    public Long currentAgentId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new FgcBusinessException(FgcErrorCode.AUTH_002);
        }
        if (authentication.getAuthorities().stream()
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

    /** 목록 조회의 범위. 요청이 다른 설계사를 지정하면 빈 목록으로 답하지 않고 조회 전에 거절한다. */
    public Long listScope(Long requestedAgentId) {
        Long agentId = currentAgentId();
        if (agentId != null && requestedAgentId != null && !agentId.equals(requestedAgentId)) denied();
        return agentId;
    }

    /** 목록·count·CSV에 같은 predicate. 계약은 최초 연결 설계사로만 판정한다(담당 변경·승계 없음). */
    public BooleanExpression contractPredicate(NumberPath<Long> solicitorId, Long requestedAgentId) {
        Long agentId = listScope(requestedAgentId);
        return agentId == null ? null : solicitorId.eq(agentId);
    }

    public void requireContract(Long contractId) {
        check("SELECT count(*) FROM fgc.insurance_contract WHERE contract_id = :id AND agent_id = :agentId", contractId);
    }

    public void requirePayment(Long paymentId) {
        check("SELECT count(*) FROM fgc.commission_transaction ct WHERE ct.commission_transaction_id = :id AND "
                + OWN_PAYMENT, paymentId);
    }

    /** 환수는 기지급 귀속행의 실제 수령인으로 판정한다. */
    public void requireClawback(Long caseId) {
        check("""
                SELECT count(*) FROM fgc.clawback_line l
                JOIN fgc.transaction_attribution a ON a.transaction_attribution_id = l.transaction_attribution_id
                JOIN fgc.commission_transaction t ON t.commission_transaction_id = a.commission_transaction_id
                WHERE l.clawback_case_id = :id AND t.recipient_agent_id = :agentId AND
                """ + ownAttribution("a"), caseId);
    }

    public void requireInquiry(Long inquiryId) {
        check("SELECT count(*) FROM fgc.inquiry WHERE inquiry_id = :id AND agent_id = :agentId"
                + " AND created_by = :userId", inquiryId);
    }

    public void requireComparison(Long explanationId) {
        check("SELECT count(*) FROM fgc.compare_explanation WHERE compare_explanation_id = :id"
                + " AND agent_id = :agentId", explanationId);
    }

    /**
     * 문서번호뿐 아니라 요청한 PDF 판의 생성자를 확인한다. 환수 통지서(CLAWBACK_NOTICE)는 FUN-084에서
     * 내부 관리 기능으로 정해져 수신자라도 AGENT에는 열지 않는다(#671).
     */
    public void requireDocument(String documentNo, int version) {
        Long agentId = currentAgentId();
        if (agentId == null) return;
        var query = entityManager.createNativeQuery("""
                SELECT count(*) FROM fgc.generated_document d
                WHERE d.document_no = :documentNo AND d.version_no = :version
                  AND d.document_kind = 'COMPARE_CONFIRMATION' AND d.source_entity_type = 'COMPARE_EXPLANATION'
                  AND EXISTS (SELECT 1 FROM fgc.compare_explanation e
                    WHERE e.compare_explanation_id = d.source_entity_id AND e.agent_id = :agentId)
                """);
        query.setParameter("documentNo", documentNo).setParameter("version", version)
                .setParameter("agentId", agentId);
        if (((Number) query.getSingleResult()).longValue() == 0) denied();
    }

    /** 문의·AI 도구는 조회/저장 전에 호출한다. 대상 종류는 고정 목록으로 해석한다. */
    public void requireTarget(String targetType, Long targetId) {
        if (currentAgentId() == null) return;
        switch (targetType == null ? "" : targetType) {
            case "CONTRACT" -> requireContract(targetId);
            case "TRANSACTION" -> requirePayment(targetId);
            case "CLAWBACK" -> requireClawback(targetId);
            default -> denied();
        }
    }

    private void check(String sql, Long id) {
        Long agentId = currentAgentId();
        if (agentId == null) return;
        if (id == null) denied();
        var query = entityManager.createNativeQuery(sql).setParameter("id", id).setParameter("agentId", agentId);
        if (sql.contains(":userId")) query.setParameter("userId", userId());
        if (((Number) query.getSingleResult()).longValue() == 0) denied();
    }

    private Long userId() {
        var principal = (FgcUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal.getUserId() == null) denied();
        return principal.getUserId();
    }

    private static void denied() {
        throw new FgcBusinessException(FgcErrorCode.AUTH_005);
    }
}
