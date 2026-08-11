package com.susukkang.fgc.transaction.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;

import java.util.Map;

/**
 * 설명 : 지급 건 확정 검증 실패 이력을 커밋한 뒤 API 오류로 전달하는 예외
 *
 * @author yslee
 * @since 2026-08-10
 * @version 1.2
 */
public class CommissionPaymentConfirmationRejectedException extends FgcBusinessException {

    public CommissionPaymentConfirmationRejectedException(FgcErrorCode errorCode) {
        super(errorCode);
    }

    public CommissionPaymentConfirmationRejectedException(
            FgcErrorCode errorCode,
            Map<String, Object> params
    ) {
        super(errorCode, params);
    }
}
