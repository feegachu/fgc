package com.susukkang.fgc.base.repository;

import com.susukkang.fgc.base.dto.AgentRow;
import com.susukkang.fgc.base.entity.Agent;
import com.susukkang.fgc.common.code.AgentRankCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

/**
 * 설명 : 설계사 기준정보 및 조직 계층별 활성 설계사 조회 Repository
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
public interface AgentRepository extends JpaRepository<Agent, Long> {

    @Query(value = """
            SELECT new com.susukkang.fgc.base.dto.AgentRow(
                a.agentId,
                a.agentCode,
                a.agentName,
                CAST(a.rankCode AS String),
                a.organizationId,
                o.organizationCode,
                o.organizationName,
                a.appointmentDate,
                a.terminationDate,
                a.agentStatus,
                a.latestRegistrationDate,
                a.priorThreeYearExperienceYn,
                a.experienceCheckedOn,
                a.newcomerSupportEligibleYn,
                a.newcomerSupportEndDate,
                a.activeYn
            )
            FROM Agent a
            JOIN Organization o ON o.organizationId = a.organizationId
            WHERE a.appointmentDate <= :asOf
              AND (a.terminationDate IS NULL OR a.terminationDate >= :asOf)
              AND (CAST(:organizationId AS Long) IS NULL OR a.organizationId = :organizationId)
              AND (
                  CAST(:keyword AS String) IS NULL
                  OR LOWER(a.agentCode) LIKE CONCAT('%', LOWER(CAST(:keyword AS String)), '%')
                  OR LOWER(a.agentName) LIKE CONCAT('%', LOWER(CAST(:keyword AS String)), '%')
              )
            """,
            countQuery = """
            SELECT COUNT(a)
            FROM Agent a
            WHERE a.appointmentDate <= :asOf
              AND (a.terminationDate IS NULL OR a.terminationDate >= :asOf)
              AND (CAST(:organizationId AS Long) IS NULL OR a.organizationId = :organizationId)
              AND (
                  CAST(:keyword AS String) IS NULL
                  OR LOWER(a.agentCode) LIKE CONCAT('%', LOWER(CAST(:keyword AS String)), '%')
                  OR LOWER(a.agentName) LIKE CONCAT('%', LOWER(CAST(:keyword AS String)), '%')
              )
            """)
    Page<AgentRow> search(
            @Param("organizationId") Long organizationId,
            @Param("keyword") String keyword,
            @Param("asOf") LocalDate asOf,
            Pageable pageable
    );

    /**
     * 소속 조직부터 상위 조직을 탐색해 가장 가까운 활성 설계사 ID를 반환한다.
     * 같은 조직에 여러 명이면 ID가 작은 설계사를 선택하고, 없으면 null을 반환한다.
     * 재귀 계층 탐색은 PostgreSQL 네이티브 SQL로 유지한다.
     */
    @Query(value = """
            WITH RECURSIVE organization_hierarchy AS (
                SELECT o.organization_id, o.parent_id, 0 AS hierarchy_depth
                FROM fgc.organization o
                WHERE o.organization_id = :organizationId

                UNION ALL

                SELECT parent.organization_id, parent.parent_id, child.hierarchy_depth + 1
                FROM fgc.organization parent
                JOIN organization_hierarchy child ON parent.organization_id = child.parent_id
            )
            SELECT a.agent_id
            FROM organization_hierarchy oh
            JOIN fgc.agent a ON a.organization_id = oh.organization_id
            WHERE a.rank_code = :#{#rankCode?.name()}
              AND a.agent_status = 'ACTIVE'
              AND a.active_yn = TRUE
              AND a.appointment_date <= :asOf
              AND (a.termination_date IS NULL OR a.termination_date >= :asOf)
            ORDER BY oh.hierarchy_depth ASC, a.agent_id ASC
            LIMIT 1
            """, nativeQuery = true)
    Long findActiveAgentIdFromOrganizationHierarchy(
            @Param("organizationId") Long organizationId,
            @Param("rankCode") AgentRankCode rankCode,
            @Param("asOf") LocalDate asOf
    );
}
