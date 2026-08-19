package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapExceptionCreateCommand;
import com.susukkang.fgc.cap.dto.CapExceptionInsertDTO;
import com.susukkang.fgc.cap.dto.CapExceptionResolveCommand;
import com.susukkang.fgc.cap.dto.CapExceptionStatusRow;
import com.susukkang.fgc.cap.mapper.CapExceptionMapper;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.common.code.PaymentStage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.DecimalFormat;

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
     * 설명 : 실시간·배치 한도 판정 결과가 주의 또는 위반인 경우 예외 건을 생성한다
     *
     * @param command 한도 예외 생성 명령
     * @author hjKang
     * @since 2026-08-12
     */
    @Override
    @Transactional
    public void createIfNecessary(CapExceptionCreateCommand command) {
        // 이 메서드는 실시간 사전확정(FUN-034) 전용이다 — 월 통합검증 8단계의 CAP 예외는
        // ExceptionCaseMapper.insertFromCapChecks(record_exception_detection 경유)가 만든다.
        // validationRunId 를 여기로 넘기면 키에 실행 ID가 들어가 재실행마다 중복 예외가
        // 생기므로(FGC-FUN-052 위반) 배치에서 이 메서드를 호출하지 않는다.
        // 연계 요구사항 : FGC-FUN-034, FGC-FUN-042, FGC-FUN-043
        validateCreateCommand(command);
        ExceptionType exceptionType = resolveExceptionType(command.resultStatus());
        if (exceptionType == null) return;

        capExceptionMapper.insertException(createExceptionDTO(command, exceptionType));
    }

    /**
     * 설명 : 해결조치를 기록하고 한도 예외를 해결 완료 상태로 변경한다
     *
     * @param command 한도 예외 해결 명령
     * @author hjKang
     * @since 2026-08-12
     */
    @Override
    @Transactional
    public void resolve(CapExceptionResolveCommand command) {
        // TODO 2. 예외관리 API 구현 시 로그인 사용자 ID를 actionBy로 전달하여 이 메서드를 호출한다.
        // 연계 요구사항 : FGC-FUN-052, FGC-FUN-053
        // FUN-034 한도 예외는 REJECTED를 사용하지 않고 RESOLVED만 종결 상태로 사용한다.
        validateResolveCommand(command);
        CapExceptionStatusRow exception = capExceptionMapper.selectExceptionForUpdate(command.exceptionCaseId());
        if (exception == null) throw new IllegalArgumentException("존재하지 않는 한도 예외입니다.");
        if ("RESOLVED".equals(exception.status())) return;
        // 미처리(OPEN = NEW + IN_REVIEW) 정의는 ExceptionStatus 한 곳만 쓴다(#83).
        if (!ExceptionStatus.isOpen(exception.status())) {
            throw new IllegalStateException("해결할 수 없는 예외 상태입니다.");
        }

        if (capExceptionMapper.insertExceptionAction(command) != 1) {
            throw new IllegalStateException("한도 예외 해결조치 저장에 실패했습니다.");
        }
        if (capExceptionMapper.updateExceptionResolved(command.exceptionCaseId()) != 1) {
            throw new IllegalStateException("한도 예외 상태 변경에 실패했습니다.");
        }
    }

    /**
     * 설명 : 지급 건에 미해결 한도 위반 예외가 존재하는지 확인한다
     *
     * @param paymentId 지급 건 ID
     * @return 미해결 한도 위반 예외 존재 여부
     * @author hjKang
     * @since 2026-08-12
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasUnresolvedViolation(Long paymentId) {
        // FUN-034의 OPEN은 공통 예외 상태인 NEW와 IN_REVIEW로 해석한다(정의: ExceptionStatus).
        // SQL 은 CapExceptionMapper.existsUnresolvedViolation 의 IN ('NEW','IN_REVIEW') — 같은 정의.
        if (paymentId == null) throw new IllegalArgumentException("지급 건 ID가 없습니다.");
        return capExceptionMapper.existsUnresolvedViolation(paymentId);
    }

    private ExceptionType resolveExceptionType(CapResultStatus resultStatus) {
        return switch (resultStatus) {
            case WARNING -> ExceptionType.CAP_WARNING;
            case VIOLATION -> ExceptionType.CAP_VIOLATION;
            default -> null;
        };
    }

    private CapExceptionInsertDTO createExceptionDTO(
            CapExceptionCreateCommand command,
            ExceptionType exceptionType
    ) {
        ExceptionSeverity severity = exceptionType == ExceptionType.CAP_VIOLATION
                ? ExceptionSeverity.CRITICAL
                : ExceptionSeverity.WARNING;
        // 목업 편집 관행(title = '판정 — 수치 근거')을 따라 건별 수치를 제목에 담는다.
        DecimalFormat amountFormat = new DecimalFormat("#,##0.##");
        String title = exceptionType == ExceptionType.CAP_VIOLATION
                ? "1,200% 한도 초과 — 사용률 " + command.usagePct() + "%"
                : "1,200% 주의 — 잔여 한도 " + amountFormat.format(command.remainingAmount()) + "원";

        return CapExceptionInsertDTO.builder()
                .exceptionKey(createExceptionKey(
                        exceptionType,
                        command.validationRunId(),
                        command.paymentId(),
                        command.paymentStage(),
                        command.capRuleSetId()
                ))
                .exceptionType(exceptionType)
                .severity(severity)
                .validationRunId(command.validationRunId())
                .contractId(command.contractId())
                .agentId(command.agentId())
                .policyVersionId(command.policyVersionId())
                .paymentId(command.paymentId())
                .title(title)
                .description(createDescription(command))
                .build();
    }

    private String createExceptionKey(
            ExceptionType exceptionType,
            Long validationRunId,
            Long paymentId,
            PaymentStage paymentStage,
            Long capRuleSetId
    ) {
        return exceptionType.name()
                + ":" + validationRunId
                + ":COMMISSION_TRANSACTION"
                + ":" + paymentId
                + ":" + paymentStage.name()
                + ":" + capRuleSetId;
    }

    private String createDescription(CapExceptionCreateCommand command) {
        BigDecimal exceededAmount = command.includedAmount()
                .subtract(command.limitAmount())
                .max(BigDecimal.ZERO);

        // 계산 근거 전체(검증 ID·룰셋·스냅샷 JSON)는 cap_check 원천 행이 보존한다 —
        // 예외 설명에는 관리자가 판단에 쓰는 수치만 사람이 읽는 문장으로 남긴다.
        DecimalFormat amount = new DecimalFormat("#,##0.##");
        return "지급단계 " + command.paymentStage()
                + " · 기준일 " + command.asOfDate()
                + " · 한도액 " + amount.format(command.limitAmount()) + "원"
                + " · 산입액 " + amount.format(command.includedAmount()) + "원"
                + " · 잔여 " + amount.format(command.remainingAmount()) + "원"
                + " · 사용률 " + command.usagePct() + "%"
                + " · 초과액 " + amount.format(exceededAmount) + "원";
    }

    private void validateCreateCommand(CapExceptionCreateCommand command) {
        if (command == null) throw new IllegalArgumentException("한도 예외 생성 명령이 없습니다.");
        if (command.paymentId() == null) throw new IllegalArgumentException("지급 건 ID가 없습니다.");
        if (command.contractId() == null) throw new IllegalArgumentException("계약 ID가 없습니다.");
        if (command.agentId() == null) throw new IllegalArgumentException("설계사 ID가 없습니다.");
        if (command.policyVersionId() == null) throw new IllegalArgumentException("정책 버전 ID가 없습니다.");
        if (command.paymentStage() == null) throw new IllegalArgumentException("지급 단계가 없습니다.");
        if (command.capRuleSetId() == null) throw new IllegalArgumentException("한도 룰셋 ID가 없습니다.");
        if (command.resultStatus() == null) throw new IllegalArgumentException("한도 판정 결과가 없습니다.");
        if (command.resultStatus() == CapResultStatus.WARNING
                || command.resultStatus() == CapResultStatus.VIOLATION) {
            if (command.limitAmount() == null || command.includedAmount() == null
                    || command.remainingAmount() == null || command.usagePct() == null) {
                throw new IllegalArgumentException("한도 예외 계산 근거가 없습니다.");
            }
        }
    }

    private void validateResolveCommand(CapExceptionResolveCommand command) {
        if (command == null) throw new IllegalArgumentException("한도 예외 해결 명령이 없습니다.");
        if (command.exceptionCaseId() == null) throw new IllegalArgumentException("예외 ID가 없습니다.");
        if (command.actionType() == null) throw new IllegalArgumentException("해결조치 코드는 필수입니다.");
        if (!StringUtils.hasText(command.reason())) throw new IllegalArgumentException("해결 사유는 필수입니다.");
        if (command.actionBy() == null) throw new IllegalArgumentException("처리자 ID가 없습니다.");
    }
}
