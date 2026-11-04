package com.susukkang.fgc.dataset;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 생성기가 DB에 넣을 기초 데이터 전체. DB 기술키 없이 업무코드만 가진다(시드명세 §0 원칙 7·8).
 * 스케줄·1,200% 결과·분개·대사는 포함하지 않는다. 그것은 실제 엔진·배치가 만든다.
 */
public record Dar012Plan(
        String datasetId,
        long seed,
        LocalDate baseDate,
        List<OrgRow> organizations,
        List<AgentRow> agents,
        List<AgentCodeRow> agentCodes,
        List<ContractRow> contracts,
        List<EventRow> events,
        List<SnapshotRow> snapshots,
        List<BatchRow> batches,
        List<PaymentRow> payments,
        List<AnswerRow> answers) {

    public record OrgRow(String code, String name, String type, String parentCode) {}

    public record AgentRow(String code, String name, String orgCode, String status,
                           LocalDate appointmentDate, LocalDate terminationDate) {}

    public record AgentCodeRow(String insurerCode, String agentCode, String insurerAgentCode) {}

    public record ContractRow(String contractNo, String insurerCode, String agentCode, String orgCode,
                              LocalDate contractDate, BigDecimal premiumPerCycle, BigDecimal monthlyEquivalent,
                              String paymentCycleCode, String conversionRuleCode, int paymentTermMonths,
                              String currentStatus) {}

    public record EventRow(String contractNo, int seq, String previousStatus, String newStatus,
                           OffsetDateTime effectiveAt, OffsetDateTime receivedAt, String reasonCode) {}

    public record SnapshotRow(String contractNo, LocalDate asOfDate, BigDecimal cumulativePaidPremium) {}

    public record BatchRow(String insurerCode, LocalDate settlementMonth, String externalStatementNo,
                           LocalDate receivedOn, String sourceRef) {}

    public record PaymentRow(String businessKey, String insurerCode, String contractNo, String insurerAgentCode,
                             LocalDate settlementMonth, LocalDate dueDate, LocalDate paidOn, BigDecimal amount,
                             int installmentNo) {}

    /** 심은 이상의 정답표 한 줄. expectedAmount 가 null 이면 엔진이 계산하는 값이라 정답표에 금액이 없다. */
    public record AnswerRow(String contractNo, String scenario, String expectedResultType,
                            BigDecimal expectedAmount, String note) {}
}
