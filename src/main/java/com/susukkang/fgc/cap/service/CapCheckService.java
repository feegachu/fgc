package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCheckBasisResponse;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.dto.CapCheckSearchCriteria;
import com.susukkang.fgc.cap.dto.CapCheckSearchResult;
import com.susukkang.fgc.common.code.PaymentStage;

import java.util.Optional;

/**
 * CapCalculator(계산)와 저장을 하나로 묶는 오케스트레이션 계층
 */
public interface CapCheckService {

    /** 계산을 실행하고 cap_check/cap_check_detail 을 append-only 로 저장한 뒤 결과를 돌려준다. */
    CapCheckSaveResult calculateAndSave(CapCalculationCommand command);

    /** 계약·지급단계의 가장 최근 저장된 판정 결과를 조회한다. 재계산하지 않는다. */
    Optional<CapCheckSaveResult> findLatest(Long contractId, PaymentStage paymentStage);

    /** IF-API-30: 목록 검색(페이징) + 요약 카드 4장. */
    CapCheckSearchResult search(CapCheckSearchCriteria criteria, int page, int size);

    /**
     * IF-API-31(계산근거 팝업, FUN-035): capCheckId로 저장된 계산 스냅샷을 그대로 펼쳐서 돌려준다.
     * 재계산하지 않는다. 해당 capCheckId가 없으면 Optional.empty() — 컨트롤러에서 404로 매핑한다.
     */
    Optional<CapCheckBasisResponse> findDetail(Long capCheckId);
}
