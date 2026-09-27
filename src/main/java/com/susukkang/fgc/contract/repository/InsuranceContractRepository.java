package com.susukkang.fgc.contract.repository;

import com.susukkang.fgc.contract.entity.InsuranceContract;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 설명 : 보험계약 엔티티의 저장·단건 조회·존재 여부 및 계약번호 중복 확인을 담당한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
public interface InsuranceContractRepository extends JpaRepository<InsuranceContract,Long> {
    /** 동일 보험회사에 같은 계약번호가 이미 등록되어 있는지 확인한다. */
    boolean existsByInsurerIdAndContractNo(Long insurerId, String contractNo);
}
