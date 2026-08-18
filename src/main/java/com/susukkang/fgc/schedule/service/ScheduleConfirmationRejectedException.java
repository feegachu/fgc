package com.susukkang.fgc.schedule.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;

/**
 * 스케줄 확정 게이트가 거절된 경우의 업무 예외다.
 * 계산근거와 검토 케이스는 보존하되, 스케줄 상태 전이만 수행하지 않는다.
 */
public class ScheduleConfirmationRejectedException extends FgcBusinessException {

    public ScheduleConfirmationRejectedException(FgcErrorCode errorCode) {
        super(errorCode);
    }
}
