package com.susukkang.fgc.validation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapRuleSetView;
import com.susukkang.fgc.cap.dto.RefundRateTableView;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.ProductOfferingSnapshotView;
import com.susukkang.fgc.validation.dto.ValidationRunInsertRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.PolicySnapshotMapper;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ValidationRunCreateServiceImpl implements ValidationRunCreateService {

    private final ValidationRunMapper validationRunMapper;
    private final PolicySnapshotMapper policySnapshotMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ValidationRunRow create(CreateValidationRunCommand command) {
        // 1. MONTHLY 중복 체크
        if(command.runType()==ValidationRunType.MONTHLY
                && validationRunMapper.existsActiveMonthlyRun(command.validationMonth())){
            throw new FgcBusinessException(FgcErrorCode.VRUN_001, Map.of());
        }

        // 2. run_no 채번
        int runNo = validationRunMapper.findNextRunNo(command.validationMonth());

        // 3. policy_snapshot
        List<CapRuleSetView> capRuleSets = policySnapshotMapper.findActiveCapRuleSets(command.validationMonth());
        List<RefundRateTableView> refundRateTables = policySnapshotMapper.findActiveRefundRateTables(command.validationMonth());
        List<ProductOfferingSnapshotView> productOfferings = policySnapshotMapper.findActiveProductOfferings(command.validationMonth());

        Map<String, Object> policySnapshot = new HashMap<>();
        policySnapshot.put("capRuleSets", capRuleSets);
        policySnapshot.put("refundRateTables", refundRateTables);
        policySnapshot.put("productOfferings", productOfferings);

        // 4. INSERT
        ValidationRunInsertRow row = ValidationRunInsertRow.builder()
                .validationMonth(command.validationMonth())
                .runType(command.runType().name())
                .runNo(runNo)
                .triggeredBy(command.triggeredBy())
                .policySnapshotJson(writeJson(policySnapshot))
                .build();
        validationRunMapper.insert(row);

        // 5. 반환
        return validationRunMapper.findById(row.getValidationRunId());
    }

    // 정책 스냅샷 Map을 JSON 문자열로 직렬화
    // 실패하면 "빈 스냅샷으로 조용히 저장" 대신 예외로 트랜잭션을 롤백
    private String writeJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_500,
                    Map.of("requestId", "policy_snapshot 직렬화 실패: " + e.getMessage()));
        }
    }
}
