package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.dto.CapCheckSearchCriteria;
import com.susukkang.fgc.cap.dto.CapCheckSearchResult;
import com.susukkang.fgc.common.code.PaymentStage;

import java.util.List;
import java.util.Optional;

/**
 * CapCalculator(계산)와 저장을 하나로 묶는 오케스트레이션 계층(#3).
 * "계산과 저장의 트랜잭션 경계는 이 계층에서 관리한다"는 요구사항에 따라, cap_check/cap_check_detail
 * 저장과 그 트랜잭션 범위를 여기서 책임진다. CapCalculator 자체는 저장을 모른다.
 */
public interface CapCheckService {

    /** 계산을 실행하고 cap_check/cap_check_detail 을 append-only 로 저장한 뒤 결과를 돌려준다. */
    CapCheckSaveResult calculateAndSave(CapCalculationCommand command);

    /** 계약·지급단계의 가장 최근 저장된 판정 결과를 조회한다. 재계산하지 않는다. */
    Optional<CapCheckSaveResult> findLatest(Long contractId, PaymentStage paymentStage);

    /** IF-API-14: 계약 1건의 지급단계별(최대 2건) 최신 판정. 합산하지 않고 각 단계 그대로 돌려준다. */
    List<CapCheckSaveResult> findByContract(Long contractId);

    /** IF-API-31: cap_check 1건(+상세 근거)을 PK로 조회한다. 없으면 empty. */
    Optional<CapCheckSaveResult> findById(Long capCheckId);

    /** IF-API-30: 목록 검색(페이징) + 요약 카드 4장. */
    CapCheckSearchResult search(CapCheckSearchCriteria criteria, int page, int size);
}
