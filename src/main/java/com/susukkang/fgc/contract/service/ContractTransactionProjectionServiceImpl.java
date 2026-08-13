package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.dto.ContractReconciliationResponse;
import com.susukkang.fgc.contract.dto.ContractTransactionAttributionResponse;
import com.susukkang.fgc.contract.dto.ContractTransactionAttributionRow;
import com.susukkang.fgc.contract.dto.ContractTransactionResponse;
import com.susukkang.fgc.contract.dto.ContractTransactionTabResponse;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import com.susukkang.fgc.contract.mapper.ContractTransactionProjectionMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 계약 상세 화면(CONT-W02) 지급·대사 탭 — commission_transaction/transaction_attribution을
 * 계약 기준으로 조회해 지급 건 단위로 묶고, reconciliation_result도 함께 조회하는 조회 전용 서비스.
 */
@Service
public class ContractTransactionProjectionServiceImpl implements ContractTransactionProjectionService {

    private final ContractTransactionProjectionMapper contractTransactionProjectionMapper;
    private final ContractMapper contractMapper;

    public ContractTransactionProjectionServiceImpl(
            ContractTransactionProjectionMapper contractTransactionProjectionMapper,
            ContractMapper contractMapper) {
        this.contractTransactionProjectionMapper = contractTransactionProjectionMapper;
        this.contractMapper = contractMapper;
    }

    @Override
    public ContractTransactionTabResponse findTransactionsByContractId(Long contractId) {
        // 1. 계약 존재 확인
        if (contractMapper.selectContractById(contractId) == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", contractId));
        }

        // 2. 이 계약에 귀속된 모든 귀속행을 한 번에 조회(commission_transaction_id, attribution_seq 순 정렬)
        List<ContractTransactionAttributionRow> rows =
                contractTransactionProjectionMapper.findTransactionAttributionsByContractId(contractId);

        // 2-1. 이 계약의 대사 결과 목록도 함께 조회(reconciliation_result, 최신순)
        List<ContractReconciliationResponse> reconciliations =
                contractTransactionProjectionMapper.findReconciliationResultsByContractId(contractId).stream()
                        .map(ContractReconciliationResponse::from)
                        .toList();

        // 3. 귀속된 지급 건이 없으면 지급 건 목록은 빈 리스트로 반환 — 오류 아님
        if (rows.isEmpty()) {
            return new ContractTransactionTabResponse(List.of(), reconciliations);
        }

        // 4. 귀속행들을 commissionTransactionId 기준으로 묶는다 — 지급 건 하나가
        // 여러 귀속행(분할 귀속)으로 흩어져 있던 것을 지급 건 단위 그룹으로 되돌림
        LinkedHashMap<Long, List<ContractTransactionAttributionRow>> grouped = new LinkedHashMap<>();
        for (ContractTransactionAttributionRow row : rows) {
            grouped.computeIfAbsent(row.getCommissionTransactionId(), key -> new ArrayList<>()).add(row);
        }

        // 5. 그룹(=지급 건 하나)마다 응답 1건을 조립
        List<ContractTransactionResponse> result = new ArrayList<>();
        for (List<ContractTransactionAttributionRow> group : grouped.values()) {
            // 지급 건 공통 필드는 같은 그룹의 아무 행에서나 꺼내도 동일하다
            // (commission_transaction 컬럼이라)
            ContractTransactionAttributionRow first = group.get(0);

            // 그룹 안의 각 귀속행을 응답용 DTO로 바꾸면서 귀속 합계를 같이 누적
            List<ContractTransactionAttributionResponse> attributions = new ArrayList<>();
            BigDecimal attributionTotal = BigDecimal.ZERO;
            for (ContractTransactionAttributionRow row : group) {
                InclusionDecisionStatus inclusionStatus = InclusionDecisionStatus.valueOf(row.getInclusionStatus());
                attributions.add(ContractTransactionAttributionResponse.builder()
                        .transactionAttributionId(row.getTransactionAttributionId())
                        .attributedAmount(row.getAttributedAmount())
                        .attributionDate(row.getAttributionDate())
                        .inclusionStatus(row.getInclusionStatus())
                        .inclusionStatusLabel(inclusionStatus.label())
                        .agentId(row.getAgentId())
                        .agentName(row.getAgentName())
                        .agentCode(row.getAgentCode())
                        .build());
                attributionTotal = attributionTotal.add(row.getAttributedAmount());
            }

            // 상태(DRAFT/CONFIRMED/CANCELLED)·지급단계 코드를 화면 표시 라벨로 변환
            CommissionPaymentStatus status = CommissionPaymentStatus.valueOf(first.getStatus());
            PaymentStage paymentStage = PaymentStage.valueOf(first.getPaymentStage());

            BigDecimal differenceAmount = first.getAmount().subtract(first.getTransactionAttributedTotal());

            // 지급 건 + 귀속 합계 + 차액 + 귀속행 리스트를 하나의 응답으로 조립
            result.add(ContractTransactionResponse.builder()
                    .commissionTransactionId(first.getCommissionTransactionId())
                    .settlementMonth(first.getSettlementMonth())
                    .paymentStage(first.getPaymentStage())
                    .paymentStageLabel(paymentStage.label())
                    .sourceType(first.getSourceType())
                    .attributions(attributions)
                    .differenceAmount(differenceAmount)
                    .attributionTotal(attributionTotal)
                    .amount(first.getAmount())
                    .status(first.getStatus())
                    .statusLabel(status.label())
                    .build());
        }

        // 6. 지급 건 등장 순서(Mapper 정렬 그대로)와 대사 결과를 함께 반환
        return new ContractTransactionTabResponse(result, reconciliations);
    }
}
