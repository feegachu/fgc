package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.FeeRegimeDecisionInput;
import com.susukkang.fgc.policy.repository.PolicyVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 설명 : 분급 수수료체계 판정 입력 조회 서비스 구현체
 */
// 호출자가 업무 예외를 삼켜도 호출자 트랜잭션이 rollback-only 가 되지 않도록 CommissionPolicyServiceImpl 과 같이 둔다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = FgcBusinessException.class)
public class FeeRegimeDecisionInputServiceImpl implements FeeRegimeDecisionInputService {

    private final PolicyVersionRepository policyVersionRepository;

    @Override
    public FeeRegimeDecisionInput getByContractId(Long contractId) {
        if (contractId == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "contractId",
                    Map.of("contractId", "null"),
                    "분급 체계 판정 입력 조회 조건이 올바르지 않습니다."
            );
        }
        return policyVersionRepository.findFeeRegimeDecisionInput(contractId)
                .map(row -> new FeeRegimeDecisionInput(
                        row.getContractId(),
                        row.getContractDate(),
                        row.getProductOfferingId(),
                        row.getOfferingVersion(),
                        row.getSalesStartDate(),
                        row.getSalesEndDate(),
                        row.getBasicDocumentVersion(),
                        row.getBasicDocumentDate(),
                        row.getDeclaredFeeRegimeCode()))
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", contractId)));
    }
}
