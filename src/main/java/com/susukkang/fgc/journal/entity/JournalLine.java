package com.susukkang.fgc.journal.entity;

import com.susukkang.fgc.common.code.PaymentStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 설명 : 내부 검증원장 분개 라인 엔티티
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Entity
@Table(
        name = "journal_line",
        schema = "fgc",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_journal_line", columnNames = {"journal_header_id", "line_no"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JournalLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "journal_line_id", nullable = false)
    private Long journalLineId;

    @Column(name = "journal_header_id", nullable = false)
    private Long journalHeaderId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "journal_account_id", nullable = false)
    private Long journalAccountId;

    @Column(name = "debit_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal debitAmount = BigDecimal.ZERO;

    @Column(name = "credit_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal creditAmount = BigDecimal.ZERO;

    @Column(name = "contract_id")
    private Long contractId;

    @Column(name = "agent_id")
    private Long agentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_stage", length = 20)
    private PaymentStage paymentStage;

    @Column(name = "commission_item_id")
    private Long commissionItemId;

    @Column(name = "memo", length = 500)
    private String memo;

    // 생성 시각은 DB 기본값을 사용한다.
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private JournalLine(Long journalHeaderId, Integer lineNo, Long journalAccountId,
                        BigDecimal debitAmount, BigDecimal creditAmount, Long contractId,
                        Long agentId, PaymentStage paymentStage, Long commissionItemId, String memo) {
        this.journalHeaderId = journalHeaderId;
        this.lineNo = lineNo;
        this.journalAccountId = journalAccountId;
        this.debitAmount = debitAmount;
        this.creditAmount = creditAmount;
        this.contractId = contractId;
        this.agentId = agentId;
        this.paymentStage = paymentStage;
        this.commissionItemId = commissionItemId;
        this.memo = memo;
    }
}
