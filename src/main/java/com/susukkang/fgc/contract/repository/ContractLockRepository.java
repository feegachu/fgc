package com.susukkang.fgc.contract.repository;

import com.susukkang.fgc.contract.entity.InsuranceContract;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 설명 : 계약 수정·스케줄 생성·한도 저장이 공유하는 PostgreSQL 계약 행 잠금.
 * 호출자 트랜잭션의 변경을 먼저 flush하고, 잠금 후 필요한 계약 엔티티만 새로고침한다.
 *
 * @author hjKang
 * @since 2026-10-11
 * @version 1.0
 */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ContractLockRepository {
    private final EntityManager entityManager;

    public Long lock(Long contractId) {
        // 상위 계약 등록·수정의 미반영 값을 보존한다. clear로 다른 엔티티를 분리하지 않는다.
        entityManager.flush();
        List<?> rows = entityManager.createNativeQuery("""
                SELECT contract_id FROM fgc.insurance_contract
                 WHERE contract_id = :contractId FOR UPDATE
                """, Long.class).setParameter("contractId", contractId).getResultList();
        return rows.isEmpty() ? null : ((Number) rows.getFirst()).longValue();
    }

    public Long lockAndRefresh(Long contractId) {
        Long locked = lock(contractId);
        if (locked != null) {
            // 이미 관리 중인 오래된 엔티티도 잠금 획득 이후의 DB 상태로 교체한다.
            entityManager.refresh(entityManager.find(InsuranceContract.class, locked));
        }
        return locked;
    }
}
