package com.susukkang.fgc.contract.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * IF-API-64 저장하지 않은 상태 사건의 변경 전후. 입력한 사건이 일어났을 때의 조건부 비교이며 계약 유지 확률이 아니다.
 * 금액 null은 자료·근거가 없어 계산하지 않았다는 뜻이고 실제 0과 구분한다.
 *
 * @param stoppedLines     멈추는 회차(nextState HOLD 보류, CANCELLED 중단)
 * @param resumedLines     재개되는 회차(nextState PLANNED)
 * @param excludedLines    바꾸지 않는 회차와 그 사유(확정, 월잠금, 소급 불가)
 * @param payments         미확정 지급 건의 보류(HELD), 해제(RELEASED), 제외(EXCLUDED)
 * @param heldAmount       이 계약의 사건으로 보류된 미확정 지급 건의 귀속액 합계
 * @param paidAmount       지급단계별 기지급 합계. 상태 사건은 기지급을 바꾸지 않으므로 변경 전후가 같다
 * @param pendingFollowUps 끝나지 않은 앞 사건의 후속 작업 수. 0이 아니면 실제 결과가 이 비교와 달라질 수 있다
 * @param snapshotAt       비교에 쓴 읽기 스냅샷 시각
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ContractStatusPreviewResponse(
        Long contractId,
        ContractStatus currentStatus,
        ContractStatus eventType,
        OffsetDateTime effectiveAt,
        List<PreviewLine> stoppedLines,
        List<PreviewLine> resumedLines,
        List<PreviewLine> excludedLines,
        List<PreviewPayment> payments,
        HeldAmount heldAmount,
        List<PaidAmount> paidAmount,
        ClawbackReview clawbackReview,
        long pendingFollowUps,
        OffsetDateTime snapshotAt
) {
    /** @param nextDueDate 납입 재개로 지급예정일을 다시 잡을 때만 값이 있다. 없으면 그대로다 */
    public record PreviewLine(Long scheduleLineId, PaymentStage paymentStage, String commissionItemName, Integer installmentNo,
                       LocalDate dueDate, @Schema(nullable = true) LocalDate nextDueDate,
                       @Schema(nullable = true) BigDecimal amount, String previousState, String nextState,
                       String reasonCode, String reason) {
    }

    public record PreviewPayment(Long commissionTransactionId, @Schema(nullable = true) BigDecimal amount, String previousState,
                          String nextState, String reasonCode, String reason) {
    }

    public record HeldAmount(BigDecimal before, BigDecimal after) {
    }

    /** @param amount 지급 귀속 범위가 어긋나 자료를 믿을 수 없으면 null */
    public record PaidAmount(PaymentStage paymentStage, @Schema(nullable = true) BigDecimal amount) {
    }

    /**
     * @param candidate   저장하면 환수 검토 후보가 되는 사건인가
     * @param amount      예상 환수액(확정액 아님). 계산하지 않았으면 null, 규칙상 실제 0원이면 0
     * @param reason      금액의 근거나 계산하지 않은 이유
     * @param reasonCodes 환수 후보 화면과 같은 검토 사유 코드
     */
    public record ClawbackReview(boolean candidate, @Schema(nullable = true) BigDecimal amount, String reason,
                                 List<String> reasonCodes, @Schema(nullable = true) Integer elapsedMonths,
                                 @Schema(nullable = true) Long policyVersionId, @Schema(nullable = true) String policyCode,
                                 @Schema(nullable = true) Integer policyVersionNo, List<ClawbackLine> lines) {
    }

    /** 계산 근거 한 줄. IF-API-85 계산판 행과 같은 값에 화면이 쓸 항목 이름을 더했다. */
    public record ClawbackLine(Long transactionAttributionId, String commissionItemName,
                               @Schema(nullable = true) Integer installmentNo, BigDecimal basisAmount,
                               BigDecimal appliedRatePct, BigDecimal clawbackAmount) {
    }
}
