package com.susukkang.fgc.contract.repository;

import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 설명 : 계약 상태사건을 저장하고 계약별 이력을 효력일시·사건 순번 순으로 조회한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
public interface ContractStatusEventRepository
        extends JpaRepository<ContractStatusEvent, Long> {
    /** 계약의 상태사건을 효력일시 오름차순으로 조회하며, 같은 효력일시는 사건 순번으로 정렬한다. */
    List<ContractStatusEvent> findByContractIdOrderByEffectiveAtAscEventSeqAsc(Long contractId);
}
