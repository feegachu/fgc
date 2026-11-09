package com.susukkang.fgc.exceptioncase.service;

import com.susukkang.fgc.arbitrage.dto.ReArbitrageCheckRequest;
import com.susukkang.fgc.arbitrage.service.ArbitrageService;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.common.exception.*;
import com.susukkang.fgc.contract.service.ContractService;
import com.susukkang.fgc.exceptioncase.dto.*;
import com.susukkang.fgc.exceptioncase.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

/** 외부 확인·환수·모니터링과 실제 원천 재검증을 연결한다. 송금이나 근거 없는 종결은 수행하지 않는다. */
@Service
@RequiredArgsConstructor
public class ExceptionSourceReviewService {
    private final EntityManager em;
    private final ExceptionCaseRepository cases;
    private final ExceptionCaseQueryRepository queries;
    private final ExceptionPaymentRepository sources;
    private final ExceptionCaseService actions;
    private final CapCheckService caps;
    private final ArbitrageService arbitrage;
    private final ContractService contracts;

    @Transactional
    public ExceptionReviewContext context(Long id, Long actor) {
        actions.requireReviewer(cases.findByIdForUpdate(id), actor);
        var source = sources.source(id);
        List<?> rows = em.createNativeQuery("""
                SELECT c.current_status,c.contract_date,c.contract_no,
                  c.contract_date < p.sales_start_date OR (p.sales_end_date IS NOT NULL AND c.contract_date > p.sales_end_date),
                  (SELECT r.actual_agent_id FROM fgc.reconciliation_result r WHERE source_entity_id=r.reconciliation_result_id::text
                     AND e.source_entity_type='RECONCILIATION_RESULT')
                FROM fgc.exception_case e LEFT JOIN fgc.insurance_contract c ON c.contract_id=e.contract_id
                LEFT JOIN fgc.product_offering p USING(product_offering_id) WHERE e.exception_case_id=:id
                """).setParameter("id", id).getResultList();
        Object[] row = (Object[]) rows.getFirst();
        String status = (String)row[0], contractNo = (String)row[2];
        var contractDate = row[1] instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate)row[1];
        boolean mismatch = Boolean.TRUE.equals(row[3]);
        String contract = source.contractId() == null ? null : "/contracts/" + source.contractId();
        String encoded = java.net.URLEncoder.encode(Objects.toString(contractNo, ""), java.nio.charset.StandardCharsets.UTF_8);
        var links = new ArrayList<ExceptionReviewContext.Link>();
        if (contract != null) {
            links.add(new ExceptionReviewContext.Link(mismatch ? "계약 판매버전 수정" : "계약 상태·입력 확인", contract + "/edit"));
            links.add(new ExceptionReviewContext.Link("예상 스케줄 확인·재생성", contract + "?tab=schedules"));
            links.add(new ExceptionReviewContext.Link("환수액 계산·PDF", "/clawbacks?contractNo=" + encoded));
            Object item = em.createNativeQuery("SELECT commission_item_id FROM fgc.commission_item WHERE item_code='CLAWBACK'").getResultStream().findFirst().orElse(null);
            links.add(new ExceptionReviewContext.Link("환수·상계 차감 등록", "/transactions/new?cashflowType=DEDUCTION&paymentStage=GA_TO_FC&contractId="
                    + source.contractId() + (row[4] == null ? "" : "&agentId=" + row[4]) + (item == null ? "" : "&commissionItemId=" + item)
                    + "&returnTo=%2Fexceptions%3Fselected%3D" + id));
        }
        links.add(new ExceptionReviewContext.Link("수수료·환수 규칙 확인", "/policies?tab=management"));
        if ("RECONCILIATION_RESULT".equals(source.sourceType())) links.add(new ExceptionReviewContext.Link("대사 비교 근거", "/reconciliations?resultId=" + source.sourceId()));
        boolean admin = queries.requiresAdministratorReview(id);
        String guidance = admin ? "보험사 지급기준과 계산근거를 대조하세요. 기준이 맞으면 시정 요청 문서와 GA→설계사 한도 확인을 남겨 관리자 판단으로 반려하고, 입력 오류면 원천 데이터 정정을 요청하세요. 스케줄 재생성만으로 금액이 바뀌지는 않습니다."
                : mismatch ? "계약일에 맞지 않는 판매버전입니다. 정책 신규 등록보다 계약의 판매버전을 먼저 정정하고 스케줄 재생성·재검증을 진행하세요."
                : "ARBITRAGE_CANDIDATE".equals(source.type()) ? "차익거래 검토대상은 위반 확정이 아닙니다. 해지·실효는 환수 계산·PDF·수령인별 차감 후 재검증하고, 유지·부활은 환수 규칙 확인 후 반려할 수 있습니다. 미납은 의견을 남겨 검토중으로 유지하세요."
                : "REFUND_TABLE_MISSING".equals(source.type()) ? "보험사에 환급률표를 요청하고 의견·요청 문서를 남기세요. 자료가 입력되기 전에는 검토중으로 유지하며, 이 계약은 계산 자료가 충분하지 않습니다."
                : "DATA_QUALITY".equals(source.type()) ? "기준일·금융/해약환급금 스냅샷·예정 지표의 근거를 확인하세요. 외부 자료가 없으면 의견을 남겨 보류하고, 참고 지표나 의도한 시연 자료라면 판단 근거를 남겨 반려하세요."
                : "CAP_WARNING".equals(source.type()) ? "잔여 한도를 확인하고 추가 지급 전 사전검증 계획을 기록하세요. 검토가 끝났다면 반려로 종결할 수 있습니다."
                : "원지급과 실제 송금·입금 여부를 확인하세요. 청약철회 지급이나 실제 중복 송금은 환수·차감 후 통지서와 차감 관리번호를 남겨 반려합니다. 지급 기록이 남으므로 대사 일치로 강제 해결하지 않습니다. 스케줄 밖의 정상 지급은 지급 근거를 확인해 반려하고, 확인되지 않은 보험사 입금은 의견을 남겨 검토중으로 유지하세요.";
        boolean canRecheck = "CAP_CHECK".equals(source.sourceType()) || "ARBITRAGE_CHECK".equals(source.sourceType())
                || ("POLICY_MISSING".equals(source.type()) && source.contractId() != null && !mismatch);
        return new ExceptionReviewContext(status, contractDate, mismatch, queries.requiresRejectionEvidence(id), admin, canRecheck, guidance, links);
    }

    @Transactional
    public ExceptionSourceRecheckResponse recheck(Long id, Long actor, String login) {
        sources.lockValidations(id);
        actions.requireReviewer(cases.findByIdForUpdate(id), actor);
        var source = sources.source(id);
        var context = context(id, actor);
        boolean resolved = false;
        ExceptionActionType action = ExceptionActionType.CORRECT;
        String basis = "";
        if (!context.offeringMismatch() && "POLICY_MISSING".equals(source.type()) && source.contractId() != null) {
            var schedules = contracts.regenerateSchedules(source.contractId(), "정책 없음 예외 재검증 · " + id);
            resolved = schedules.size() >= 2;
            basis = "재생성된 예상 스케줄 " + schedules;
        } else if (!context.offeringMismatch() && "CAP_CHECK".equals(source.sourceType())) {
            var scope = sources.capScope(id);
            if (scope != null) {
                var saved = caps.calculateAndSave(CapCalculationCommand.manual(source.contractId(), scope.stage(), scope.asOfDate(), null));
                var status = saved.result().resultStatus();
                resolved = status != CapResultStatus.REVIEW_REQUIRED
                        && (!"CAP_VIOLATION".equals(source.type()) || status != CapResultStatus.VIOLATION)
                        && (!"CAP_WARNING".equals(source.type()) || (status != CapResultStatus.WARNING && status != CapResultStatus.VIOLATION));
                basis = "한도 계산근거 " + saved.capCheckId();
            }
        } else if (!context.offeringMismatch() && "ARBITRAGE_CHECK".equals(source.sourceType())) {
            var stages = em.createNativeQuery("SELECT payment_stage,as_of_date FROM fgc.arbitrage_check WHERE arbitrage_check_id::text=:id")
                    .setParameter("id", source.sourceId()).getResultList();
            if (!stages.isEmpty() && "GA_TO_FC".equals(((Object[])stages.getFirst())[0])) {
                Object date = ((Object[])stages.getFirst())[1];
                LocalDate asOf = date instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) date;
                var checked = arbitrage.reArbitrageCheck(source.contractId(), new ReArbitrageCheckRequest(
                        asOf, "예외 원천 재검증 · " + id), actor);
                resolved = checked.getResultStatus() != ArbitrageCheckStatus.REVIEW_REQUIRED
                        && (!"ARBITRAGE_CANDIDATE".equals(source.type()) || checked.getResultStatus() != ArbitrageCheckStatus.CANDIDATE);
                basis = "차익거래 계산근거 " + checked.getArbitrageCheckId();
                if ("ARBITRAGE_CANDIDATE".equals(source.type()) && ((Number)em.createNativeQuery("""
                        SELECT count(*) FROM fgc.transaction_attribution a JOIN fgc.commission_transaction p USING(commission_transaction_id)
                        WHERE a.contract_id=:id AND p.payment_stage='GA_TO_FC' AND p.status='CONFIRMED' AND p.cashflow_type='DEDUCTION'
                          AND p.updated_at >= (SELECT created_at FROM fgc.exception_case WHERE exception_case_id=:caseId)
                        """).setParameter("id", source.contractId()).setParameter("caseId", id).getSingleResult()).longValue() > 0) action = ExceptionActionType.REDUCE;
            }
        }
        var receipt = resolved ? actions.action(id, new ExceptionActionRequest(action, "원천 재검증 통과 · " + basis, basis), actor, login) : null;
        return new ExceptionSourceRecheckResponse(null, resolved, resolved ? "원천 자료 재검증을 통과해 예외를 종결했습니다."
                : "검토 원인이 남아 있습니다. 외부 자료·계약 입력·적용 규칙을 확인하고 의견을 남기세요.", receipt);
    }
}
