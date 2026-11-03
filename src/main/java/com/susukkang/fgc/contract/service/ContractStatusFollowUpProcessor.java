package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.contract.dto.ContractStatusEffect;
import com.susukkang.fgc.contract.entity.ContractStatusEvent;

import java.util.List;

/**
 * 설명 : 계약 상태 사건의 후속 처리기. FUN-028(미확정 지급 보류)·FUN-029(부활 시 재개)가 구현한다.
 * 계약 수정(IF-API-19)이 사건을 저장하는 트랜잭션에서 {@link #supports}가 참인 처리기마다 대기 작업을 만들고,
 * worker가 계약 잠금을 잡은 한 트랜잭션에서 계약별 사건 순번 순으로 {@link #process}를 부른다.
 * 예외를 던지면 그 트랜잭션은 모두 되돌려지고 같은 사건으로 다시 불리므로 처리기의 쓰기는 같은 결과로 수렴해야 한다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-30
 */
public interface ContractStatusFollowUpProcessor {

    /** contract_event_job·contract_status_event_processing의 processing_job 값. 처리기마다 고유하고 100자 이하. */
    String jobName();

    /** 사건 저장 시점에 이 처리기의 작업이 필요한지 정한다. */
    boolean supports(ContractStatusEvent event);

    /** 영향과 처리하지 않은 대상의 이유를 돌려준다. 확정·기지급·월잠금 결과는 바꾸지 않는다. */
    List<ContractStatusEffect> process(ContractStatusEvent event);
}
