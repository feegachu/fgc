package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.reconciliation.dto.CreateReconciliationRunCommand;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow;

/**
 * 설명 : 대사 실행 생성 서비스
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
public interface ReconciliationRunService {

    ReconciliationRunRow create(CreateReconciliationRunCommand command);
}
