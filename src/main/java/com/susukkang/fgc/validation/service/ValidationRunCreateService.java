package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.ValidationRunRow;

/**
 * validation_run 생성만 다루는 도메인 서비스
 */
public interface ValidationRunCreateService {

    /**
     * command 조건으로 새 validation_run을 CREATED 상태로 만듬
     *
     * @return 생성된 실행의 최신 행(validationRunId, status 등)
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException
     *         (VRUN_001) command.runType()이 MONTHLY이고 해당 월에 이미 활성 실행이 있을 때
     */
    ValidationRunRow create(CreateValidationRunCommand command);
}
