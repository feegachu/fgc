package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.code.JournalHeaderStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.dto.ContractJournalLineResponse;
import com.susukkang.fgc.contract.dto.ContractJournalLineRow;
import com.susukkang.fgc.contract.dto.ContractJournalResponse;
import com.susukkang.fgc.contract.mapper.ContractJournalProjectionMapper;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 계약 상세 화면(CONT-W02) 검증원장 탭 — journal_header/journal_line을 계약 기준으로
 * 조회해 헤더 단위로 묶어 돌려주는 조회 전용 서비스.
 */
@Service
public class ContractJournalProjectionServiceImpl implements ContractJournalProjectionService {

    private final ContractJournalProjectionMapper contractJournalProjectionMapper;
    private final ContractMapper contractMapper;

    public ContractJournalProjectionServiceImpl(ContractJournalProjectionMapper contractJournalProjectionMapper,
                                                  ContractMapper contractMapper) {
        this.contractJournalProjectionMapper = contractJournalProjectionMapper;
        this.contractMapper = contractMapper;
    }

    @Override
    public List<ContractJournalResponse> findJournalsByContractId(Long contractId) {
        // 1. 계약 존재 확인
        if (contractMapper.selectContractById(contractId) == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", contractId));
        }

        // 2. 이 계약에 연결된 모든 분개 라인을 한 번에 조회(journal_header_id, line_no 순 정렬)
        List<ContractJournalLineRow> rows = contractJournalProjectionMapper.findJournalLinesByContractId(contractId);

        // 3. 연결된 분개가 없으면 빈 목록 반환
        if (rows.isEmpty()) {
            return List.of();
        }

        // 4. 라인들을 journalHeaderId 기준으로 묶는다 — 분개 하나(차변 1줄+대변 1줄
        // 이상)가 여러 행으로 흩어져 있던 것을 헤더 단위 그룹으로 되돌림
        LinkedHashMap<Long, List<ContractJournalLineRow>> grouped = new LinkedHashMap<>();
        for (ContractJournalLineRow row : rows) {
            grouped.computeIfAbsent(row.getJournalHeaderId(), key -> new ArrayList<>()).add(row);
        }

        // 5. 그룹(=분개 헤더 하나)마다 응답 1건을 조립
        List<ContractJournalResponse> result = new ArrayList<>();
        for (List<ContractJournalLineRow> group : grouped.values()) {
            // 헤더 공통 필드는 같은 그룹의 아무 행에서나 꺼내도 동일하다(journal_header 컬럼이라)
            ContractJournalLineRow first = group.get(0);

            // 그룹 안의 각 라인을 응답용 라인 DTO로 바꾸면서 차변·대변 합계를 같이 누적
            List<ContractJournalLineResponse> lines = new ArrayList<>();
            BigDecimal debitTotal = BigDecimal.ZERO;
            BigDecimal creditTotal = BigDecimal.ZERO;
            for (ContractJournalLineRow row : group) {
                lines.add(ContractJournalLineResponse.from(row));
                debitTotal = debitTotal.add(row.getDebitAmount());
                creditTotal = creditTotal.add(row.getCreditAmount());
            }

            // 상태 코드(DRAFT/POSTED/REVERSED)를 화면 표시 라벨로 변환
            JournalHeaderStatus status = JournalHeaderStatus.valueOf(first.getStatus());
            // 지급단계도 같은 방식으로 라벨을 만든다 — journal_line.payment_stage는
            // nullable이라 null 방어(ContractJournalLineResponse.from()과 같은 처리)
            PaymentStage paymentStage = first.getPaymentStage() == null
                    ? null : PaymentStage.valueOf(first.getPaymentStage());

            // 헤더 + 합계 + 차액 + 지급단계 + 라인 리스트를 하나의 응답으로 조립
            result.add(ContractJournalResponse.builder()
                    .journalHeaderId(first.getJournalHeaderId())
                    .journalNo(first.getJournalNo())
                    .journalDate(first.getJournalDate())
                    .journalType(first.getJournalType())
                    .sourceEntityType(first.getSourceEntityType())
                    .sourceEntityId(first.getSourceEntityId())
                    .revisionNo(first.getRevisionNo())
                    .policyVersionId(first.getPolicyVersionId())
                    .validationRunId(first.getValidationRunId())
                    .status(first.getStatus())
                    .statusLabel(status.label())
                    .debitTotal(debitTotal)
                    .creditTotal(creditTotal)
                    .differenceAmount(debitTotal.subtract(creditTotal).abs())
                    .description(first.getDescription())
                    .paymentStage(first.getPaymentStage())
                    .paymentStageLabel(paymentStage == null ? null : paymentStage.label())
                    .lines(lines)
                    .build());
        }

        // 6. 헤더 등장 순서(Mapper 정렬 그대로) 그대로 반환
        return result;
    }
}
