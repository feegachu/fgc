package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.cap.dto.CapRuleSetView;
import com.susukkang.fgc.cap.dto.RefundRateTableView;
import com.susukkang.fgc.validation.dto.ProductOfferingSnapshotView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * ValidationRunCreateServiceImpl이 policy_snapshot을 조립할 때 쓰는 조회 전용 Mapper
 * (FUN-041 #2). 결정된 스냅샷 범위: "asOfDate 시점에 유효한(활성) 전체" — 특정 계약이
 * 실제로 그 룰셋/환급률표/상품판매버전을 쓰는지는 안 따진다(CapRuleMapper#findApplicableRuleSet·
 * RefundRateMapper#findApplicableTable처럼 계약 1건 기준 "가장 구체적인 것 1건"을 찾는
 * 조회와는 목적이 다르다 — 여기는 "그 시점에 존재하는 것 전부"를 나열한다).
 *
 * CapRuleSetView/RefundRateTableView(cap.dto)를 그대로 재사용한다 — 이미 필요한 필드를
 * 전부 가지고 있고, 같은 테이블을 다른 모양으로 또 매핑할 이유가 없다.
 */
@Mapper
public interface PolicySnapshotMapper {

    /** payment_stage 상관없이 asOfDate 시점 유효한 cap_rule_set 전체(policy_version.status='ACTIVE'). */
    List<CapRuleSetView> findActiveCapRuleSets(@Param("asOfDate") LocalDate asOfDate);

    /** asOfDate 시점 유효한 refund_rate_table 전체(policy_version.status='ACTIVE'). */
    List<RefundRateTableView> findActiveRefundRateTables(@Param("asOfDate") LocalDate asOfDate);

    /** asOfDate 시점 판매 중(active_yn, sales_start/end_date)인 product_offering 전체. */
    List<ProductOfferingSnapshotView> findActiveProductOfferings(@Param("asOfDate") LocalDate asOfDate);
}
