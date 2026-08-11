package com.susukkang.fgc.base.mapper;

import com.susukkang.fgc.common.code.AgentRankCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 설명 : 설계사 기준정보 조회 Mapper
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-10
 */
@Mapper
public interface AgentMapper {

    /**
     * 설명 : 계약 소속 조직과 상위 조직에서 직급에 해당하는 활성 설계사 ID를 조회한다.
     * asOf를 통해서 해당 기간안에 활성화 되어있는 설계사만 가져와야한다.
     *
     * @param organizationId 조회 시작 조직 ID
     * @param rankCode 설계사 직급 코드
     * @param asOf 기준일
     * @return 지급 대상 설계사 ID
     */
    Long findActiveAgentIdFromOrganizationHierarchy(
            @Param("organizationId") Long organizationId,
            @Param("rankCode") AgentRankCode rankCode,
            @Param("asOf") LocalDate asOf
    );
}