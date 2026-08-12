package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapExceptionInsertDTO;
import com.susukkang.fgc.cap.mapper.CapExceptionMapper;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

/**
 * 설명 : 한도 검증 결과가 주의 또는 위반인 경우 중복 없는 한도 예외 건을 생성하는 서비스 구현체
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Service
@RequiredArgsConstructor
public class CapExceptionServiceImpl implements CapExceptionService {

    private final CapExceptionMapper capExceptionMapper;

    /**
     * 설명 : 1,200% 한도 계산 결과를 바탕으로 필요한 예외 건을 생성한다
     *
     * @param paymentId 지급 건 ID
     * @param agentId 설계사 ID
     * @param result 1,200% 한도 계산 결과
     * @author hjKang
     * @since 2026-08-12
     */
    @Override
    @Transactional
    public void createIfNecessary(
            Long paymentId,
            Long agentId,
            CapCalculationResult result
    ) {
        validateInput(paymentId, agentId, result);

        ExceptionType exceptionType = resolveExceptionType(result.resultStatus());

        // 정상 또는 검토필요 결과는 FUN-034 주의·위반 예외 생성 대상이 아니다.
        if (exceptionType == null) {
            return;
        }

        Long policyVersionId = capExceptionMapper.selectPolicyVersionId(
                result.capRuleSetId()
        );

        if (policyVersionId == null) {
            throw new IllegalStateException("한도 룰셋의 정책 버전을 찾을 수 없습니다.");
        }

        CapExceptionInsertDTO exception = createExceptionDTO(
                paymentId,
                agentId,
                policyVersionId,
                exceptionType,
                result
        );

        capExceptionMapper.insertException(exception);
    }

    /**
     * 설명 : 한도 판정 결과를 예외 유형으로 변환한다
     *
     * @param resultStatus 한도 판정 결과
     * @return 예외 유형
     * @author hjKang
     * @since 2026-08-12
     */
    private ExceptionType resolveExceptionType(CapResultStatus resultStatus) {
        return switch (resultStatus) {
            case WARNING -> ExceptionType.CAP_WARNING;
            case VIOLATION -> ExceptionType.CAP_VIOLATION;
            default -> null;
        };
    }

    /**
     * 설명 : 한도 예외 등록 DTO를 생성한다
     *
     * @param paymentId 지급 건 ID
     * @param agentId 설계사 ID
     * @param policyVersionId 정책 버전 ID
     * @param exceptionType 예외 유형
     * @param result 한도 계산 결과
     * @return 한도 예외 등록 DTO
     * @author hjKang
     * @since 2026-08-12
     */
    private CapExceptionInsertDTO createExceptionDTO(
            Long paymentId,
            Long agentId,
            Long policyVersionId,
            ExceptionType exceptionType,
            CapCalculationResult result
    ) {
        ExceptionSeverity severity =
                exceptionType == ExceptionType.CAP_VIOLATION
                        ? ExceptionSeverity.CRITICAL
                        : ExceptionSeverity.WARNING;

        String title =
                exceptionType == ExceptionType.CAP_VIOLATION
                        ? "1,200% 한도 초과"
                        : "1,200% 한도 사용률 주의";

        return CapExceptionInsertDTO.builder()
                .exceptionKey(createExceptionKey(paymentId, policyVersionId))
                .exceptionType(exceptionType)
                .severity(severity)
                .validationRunId(null)
                .contractId(result.contractId())
                .agentId(agentId)
                .policyVersionId(policyVersionId)
                .paymentId(paymentId)
                .title(title)
                .description(createDescription(result))
                .build();
    }

    /**
     * 설명 : 지급 건과 정책 버전을 이용하여 중복 방지용 예외 키를 생성한다
     *
     * @param paymentId 지급 건 ID
     * @param policyVersionId 정책 버전 ID
     * @return 예외 업무 고유키
     * @author hjKang
     * @since 2026-08-12
     */
    private String createExceptionKey(
            Long paymentId,
            Long policyVersionId
    ) {
        return "CAP:" + paymentId + ":CAP_CHECK:" + policyVersionId;
    }

    /**
     * 설명 : 한도 예외의 계산 근거 설명을 생성한다
     *
     * @param result 한도 계산 결과
     * @return 계산 근거 설명
     * @author hjKang
     * @since 2026-08-12
     */
    private String createDescription(CapCalculationResult result) {
        BigDecimal exceededAmount = result.includedAmount()
                .subtract(result.limitAmount())
                .max(BigDecimal.ZERO);

        return "한도액=" + result.limitAmount()
                + ", 산입액=" + result.includedAmount()
                + ", 잔여액=" + result.remainingAmount()
                + ", 사용률=" + result.usagePct()
                + "%, 초과액=" + exceededAmount;
    }

    /**
     * 설명 : 한도 예외 생성에 필요한 입력값을 검증한다
     *
     * @param paymentId 지급 건 ID
     * @param agentId 설계사 ID
     * @param result 한도 계산 결과
     * @author hjKang
     * @since 2026-08-12
     */
    private void validateInput(
            Long paymentId,
            Long agentId,
            CapCalculationResult result) {
        if (paymentId == null) {
            throw new IllegalArgumentException("지급 건 ID가 없습니다.");
        }
        if (agentId == null) {
            throw new IllegalArgumentException("설계사 ID가 없습니다.");
        }
        if (result == null) {
            throw new IllegalArgumentException("1,200% 한도 계산 결과가 없습니다.");
        }
        if (result.resultStatus() == null) {
            throw new IllegalArgumentException("1,200% 한도 판정 결과가 없습니다.");
        }
    }
}