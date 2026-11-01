package com.susukkang.fgc.contract.repository;

import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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

    /** 계약의 가장 최근 상태사건(효력일시 내림차순, 같은 효력일시는 사건 순번 내림차순 첫 행)을 조회한다. */
    Optional<ContractStatusEvent> findFirstByContractIdOrderByEffectiveAtDescEventSeqDesc(Long contractId);

    /** 계약 잠금 안에서 다음 사건 순번을 정하기 위한 최대 순번. 사건이 없으면 0. */
    @Query("SELECT COALESCE(MAX(e.eventSeq), 0) FROM ContractStatusEvent e WHERE e.contractId = :contractId")
    int findMaxEventSeq(@Param("contractId") Long contractId);
}
