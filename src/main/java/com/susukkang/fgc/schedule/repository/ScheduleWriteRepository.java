package com.susukkang.fgc.schedule.repository;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.common.code.ScheduleLineStatus;
import com.susukkang.fgc.schedule.code.SchedulePurpose;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderInsertDTO;
import com.susukkang.fgc.schedule.dto.ScheduleLineInsertDTO;
import com.susukkang.fgc.schedule.entity.ScheduleHeader;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 예상 스케줄 생성·버전 전환·확정과 검증 실행 연결의 저장 계약.
 * 호출자 트랜잭션에 참여하며 개별 저장을 별도로 커밋하지 않는다.
 * 상태 전이는 기존 조건(활성·PLANNED)을 그대로 건 벌크 갱신으로 처리해 갱신 건수로 경합을 판정한다.
 * 회차 일괄 저장·계약 잠금·검토 예외 UPSERT는 PostgreSQL 전용 의미를 유지하기 위해 네이티브 SQL이다.
 * 네이티브·벌크 변경 전에는 flush한다. 스케줄 엔티티는 DTO로만 재조회하므로 clear하지 않는다
 * (clear하면 호출자가 들고 있는 계약 엔티티까지 분리된다).
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-05
 */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ScheduleWriteRepository {

    private final EntityManager entityManager;
    private final ScheduleHeaderRepository scheduleHeaderRepository;

    private static final String LINE_INSERT = """
            INSERT INTO fgc.schedule_line (
                schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                commission_item_id, beneficiary_agent_id, basis_code, basis_amount, calculation_type,
                rate_pct, fixed_amount, expected_amount, rounding_scale, rounding_mode,
                payment_condition_code, line_status, source_commission_rule_id
            ) VALUES
            """;

    /** 헤더 한 건을 저장하고 생성된 ID를 DTO에 채운다(기존 useGeneratedKeys와 같은 계약). */
    public int insertScheduleHeader(ScheduleHeaderInsertDTO header) {
        header.setScheduleHeaderId(scheduleHeaderRepository.save(ScheduleHeader.from(header)).getScheduleHeaderId());
        return 1;
    }

    /**
     * 회차를 한 문장의 다건 VALUES로 저장한다. 문장 단위 확정 헤더 가드 트리거가 한 번만 실행되고
     * 기존 일괄 INSERT와 같은 왕복 1회를 유지한다.
     */
    public int insertAllScheduleLines(List<ScheduleLineInsertDTO> lines) {
        if (lines.isEmpty()) {
            // 기존 foreach INSERT도 빈 목록은 SQL 오류로 롤백됐다. 회차 없는 헤더를 남기지 않는다.
            throw new IllegalArgumentException("저장할 스케줄 라인이 없습니다.");
        }
        entityManager.flush();
        StringBuilder sql = new StringBuilder(LINE_INSERT);
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("(:scheduleHeaderId").append(index)
                    .append(", :lineNo").append(index)
                    .append(", :installmentNo").append(index)
                    .append(", :contractMonthNo").append(index)
                    .append(", :dueDate").append(index)
                    .append(", :commissionItemId").append(index)
                    .append(", :beneficiaryAgentId").append(index)
                    .append(", :basisCode").append(index)
                    .append(", :basisAmount").append(index)
                    .append(", :calculationType").append(index)
                    .append(", :ratePct").append(index)
                    .append(", :fixedAmount").append(index)
                    .append(", :expectedAmount").append(index)
                    .append(", :roundingScale").append(index)
                    .append(", :roundingMode").append(index)
                    .append(", :paymentConditionCode").append(index)
                    .append(", :lineStatus").append(index)
                    .append(", :sourceCommissionRuleId").append(index)
                    .append(")");
        }
        NativeQuery<?> query = entityManager.createNativeQuery(sql.toString()).unwrap(NativeQuery.class);
        for (int index = 0; index < lines.size(); index++) {
            ScheduleLineInsertDTO line = lines.get(index);
            query.setParameter("scheduleHeaderId" + index, line.getScheduleHeaderId(), Long.class);
            query.setParameter("lineNo" + index, line.getLineNo(), Integer.class);
            query.setParameter("installmentNo" + index, line.getInstallmentNo(), Integer.class);
            query.setParameter("contractMonthNo" + index, line.getContractMonthNo(), Integer.class);
            query.setParameter("dueDate" + index, line.getDueDate(), LocalDate.class);
            query.setParameter("commissionItemId" + index, line.getCommissionItemId(), Long.class);
            query.setParameter("beneficiaryAgentId" + index, line.getBeneficiaryAgentId(), Long.class);
            query.setParameter("basisCode" + index, line.getBasisCode(), String.class);
            query.setParameter("basisAmount" + index, line.getBasisAmount(), BigDecimal.class);
            query.setParameter("calculationType" + index, name(line.getCalculationType()), String.class);
            query.setParameter("ratePct" + index, line.getRatePct(), BigDecimal.class);
            query.setParameter("fixedAmount" + index, line.getFixedAmount(), BigDecimal.class);
            query.setParameter("expectedAmount" + index, line.getExpectedAmount(), BigDecimal.class);
            query.setParameter("roundingScale" + index, line.getRoundingScale(), Integer.class);
            query.setParameter("roundingMode" + index, name(line.getRoundingMode()), String.class);
            query.setParameter("paymentConditionCode" + index, line.getPaymentConditionCode(), String.class);
            query.setParameter("lineStatus" + index, name(line.getLineStatus()), String.class);
            query.setParameter("sourceCommissionRuleId" + index, line.getSourceCommissionRuleId(), Long.class);
        }
        return query.executeUpdate();
    }

    /** FGC-FUN-043 결과 집계 — 이번 실행이 만든 헤더만 validation_run_id로 표시한다. */
    public int linkHeadersToValidationRun(List<Long> scheduleHeaderIds, Long validationRunId) {
        return entityManager.createQuery("""
                UPDATE ScheduleHeader sh
                   SET sh.validationRunId = :validationRunId
                 WHERE sh.scheduleHeaderId IN :scheduleHeaderIds
                """)
                .setParameter("validationRunId", validationRunId)
                .setParameter("scheduleHeaderIds", scheduleHeaderIds)
                .executeUpdate();
    }

    /**
     * 정책 없음·중복·한도 검토로 생성·확정하지 못한 지급단계를 공통 예외 큐에 등록한다.
     * 동일 계약·지급단계·예외유형은 한 건으로 유지하며, 종결 건도 NEW로 다시 연다(기존 규칙).
     * 공용 exception_case 엔티티를 중복 정의하지 않고 ON CONFLICT 멱등 저장만 네이티브로 둔다.
     */
    public int upsertPolicyReviewCase(Long contractId, PaymentStage paymentStage, String exceptionType,
                                      String title, String description) {
        entityManager.flush();
        NativeQuery<?> query = entityManager.createNativeQuery("""
                INSERT INTO fgc.exception_case (
                    exception_key, exception_type, reason_code, severity, status, contract_id,
                    source_entity_type, source_entity_id, title, description
                ) VALUES (
                    CONCAT('SCHEDULE_POLICY:', CAST(:contractId AS varchar), ':', :paymentStage, ':', :exceptionType),
                    :exceptionType, :exceptionType, 'HIGH', 'NEW', :contractId,
                    'INSURANCE_CONTRACT', CAST(:contractId AS varchar), :title, :description
                )
                ON CONFLICT (exception_key)
                DO UPDATE SET
                    status = 'NEW',
                    reason_code = EXCLUDED.reason_code,
                    title = EXCLUDED.title,
                    description = EXCLUDED.description,
                    resolved_at = NULL,
                    updated_at = clock_timestamp()
                """).unwrap(NativeQuery.class);
        return query.setParameter("contractId", contractId, Long.class)
                .setParameter("paymentStage", name(paymentStage), String.class)
                .setParameter("exceptionType", exceptionType, String.class)
                .setParameter("title", title, String.class)
                .setParameter("description", description, String.class)
                .executeUpdate();
    }

    /** 계약 단위 스케줄 생성·재생성·확정을 직렬화하기 위해 계약 행을 잠근다. 없으면 null. */
    public Long lockContractForScheduleGeneration(Long contractId) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT contract_id
                  FROM fgc.insurance_contract
                 WHERE contract_id = :contractId
                 FOR UPDATE
                """, Long.class)
                .setParameter("contractId", contractId)
                .getResultList();
        return rows.isEmpty() ? null : ((Number) rows.getFirst()).longValue();
    }

    /** 기존 활성 운영 스케줄을 비활성화한다. */
    public int deactivateActiveOperationalSchedule(Long contractId, PaymentStage paymentStage) {
        return entityManager.createQuery("""
                UPDATE ScheduleHeader sh
                   SET sh.activeYn = FALSE
                 WHERE sh.contractId = :contractId
                   AND sh.paymentStage = :paymentStage
                   AND sh.schedulePurpose = :operational
                   AND sh.activeYn = TRUE
                """)
                .setParameter("contractId", contractId)
                .setParameter("paymentStage", paymentStage)
                .setParameter("operational", SchedulePurpose.OPERATIONAL)
                .executeUpdate();
    }

    /** 활성 헤더의 상태와 활성 여부를 바꾼다. 이미 비활성이면 0건이다. */
    public int updateScheduleHeaderStatus(Long scheduleHeaderId, ScheduleHeaderStatus scheduleHeaderStatus,
                                          boolean active) {
        return entityManager.createQuery("""
                UPDATE ScheduleHeader sh
                   SET sh.status = :status, sh.activeYn = :active
                 WHERE sh.scheduleHeaderId = :scheduleHeaderId
                   AND sh.activeYn = TRUE
                """)
                .setParameter("status", scheduleHeaderStatus)
                .setParameter("active", active)
                .setParameter("scheduleHeaderId", scheduleHeaderId)
                .executeUpdate();
    }

    /** 예정 상태인 회차를 확정 상태로 변경한다. 헤더 확정 전에 실행해야 라인 가드 트리거를 통과한다. */
    public int confirmPlannedScheduleLines(Long scheduleHeaderId) {
        return entityManager.createQuery("""
                UPDATE ScheduleLine sl
                   SET sl.lineStatus = :confirmed
                 WHERE sl.scheduleHeaderId = :scheduleHeaderId
                   AND sl.lineStatus = :planned
                """)
                .setParameter("confirmed", ScheduleLineStatus.CONFIRMED)
                .setParameter("planned", ScheduleLineStatus.PLANNED)
                .setParameter("scheduleHeaderId", scheduleHeaderId)
                .executeUpdate();
    }

    /** 활성 예정 스케줄 헤더를 확정한다. */
    public int confirmScheduleHeader(Long scheduleHeaderId) {
        return entityManager.createQuery("""
                UPDATE ScheduleHeader sh
                   SET sh.status = :confirmed
                 WHERE sh.scheduleHeaderId = :scheduleHeaderId
                   AND sh.status = :planned
                   AND sh.activeYn = TRUE
                """)
                .setParameter("confirmed", ScheduleHeaderStatus.CONFIRMED)
                .setParameter("planned", ScheduleHeaderStatus.PLANNED)
                .setParameter("scheduleHeaderId", scheduleHeaderId)
                .executeUpdate();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
