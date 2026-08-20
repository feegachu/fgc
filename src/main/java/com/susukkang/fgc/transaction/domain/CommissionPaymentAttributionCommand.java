package com.susukkang.fgc.transaction.domain;

import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 수수료 지급 건 계약별 귀속 저장 명령
 *
 * @author yslee
 * @since 2026-08-10
 * @version 1.2
 */
@Getter
@Builder
public class CommissionPaymentAttributionCommand {

    @Setter
    private Long paymentId;
    private final Integer attributionSequence;
    private final Long agentId;
    private final Long contractId;
    private final Long scheduleLineId;
    /** 대응 운영 스케줄 행의 회차. 부모 지급 건 회차를 정하는 데만 쓰고 귀속행 테이블에는 저장하지 않는다. */
    private final Integer installmentNo;
    private final LocalDate attributionDate;
    private final LocalDate attributionMonth;
    private final BigDecimal amount;
    private final InclusionDecisionStatus inclusionDecisionStatus;
    private final ExclusionType exclusionType;
    private final String inclusionDecisionReason;
    private final AttributionMethod attributionMethod;
    private final Long allocationPolicyId;
    private final String allocationBasisJson;
    private final String evidenceRef;

    public String getAttributionScope() {
        return attributionMethod == AttributionMethod.NEWCOMER_NON_CONTRACT
                ? "AGENT"
                : "CONTRACT";
    }
}
