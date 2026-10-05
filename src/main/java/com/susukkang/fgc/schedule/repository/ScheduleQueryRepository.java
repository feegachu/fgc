package com.susukkang.fgc.schedule.repository;

import com.susukkang.fgc.common.code.CalculationType;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.common.code.ScheduleLineStatus;
import com.susukkang.fgc.schedule.code.SchedulePurpose;
import com.susukkang.fgc.schedule.code.ScheduleRegime;
import com.susukkang.fgc.schedule.dto.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 설명 : 예상 스케줄 목록·상세·버전과 생성·확정에 필요한 현재 상태를 조회한다.
 * 모든 결과는 엔티티가 아닌 DTO로 읽는다. 계약 행 잠금 뒤 재조회가 영속성 컨텍스트의 이전 상태를
 * 재사용하지 않고 항상 DB의 최신 커밋 값을 보게 하기 위해서다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-10-05
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleQueryRepository {

    private final EntityManager entityManager;

    // 목록·버전·상세가 공유하는 헤더 집계. 헤더 PK로 묶으므로 헤더 컬럼은 함수 종속으로 선택된다.
    // 모든 쿼리는 리터럴만 이어 붙인 컴파일 시점 상수다. 입력값은 전부 바인딩 파라미터로만 전달한다.
    private static final String HEADER_COLUMNS = """
             sh.paymentStage, sh.scheduleRegime,
                   sh.schedulePurpose, sh.scheduleVersionNo, sh.status, sh.activeYn, sh.generatedAt,
                   sh.generationReason, COUNT(sl.scheduleLineId), SUM(sl.expectedAmount),
                   pv.policyCode, pv.versionNo
              FROM ScheduleHeader sh
              JOIN InsuranceContract c ON c.contractId = sh.contractId
              JOIN PolicyVersion pv ON pv.policyVersionId = sh.policyVersionId
            """;
    private static final String HEADER_SELECT_SIMPLE = "SELECT sh.scheduleHeaderId, c.contractNo,"
            + " CAST(NULL AS String), CAST(NULL AS String)," + HEADER_COLUMNS;
    private static final String HEADER_SELECT_DETAIL = "SELECT sh.scheduleHeaderId, c.contractNo,"
            + " i.insurerName, p.productName," + HEADER_COLUMNS + """
              JOIN Insurer i ON i.insurerId = c.insurerId
              JOIN ProductOffering po ON po.productOfferingId = c.productOfferingId
              JOIN Product p ON p.productId = po.productId
            """;
    private static final String LINE_JOIN = """
              LEFT JOIN ScheduleLine sl ON sl.scheduleHeaderId = sh.scheduleHeaderId
            """;
    private static final String GROUP_BY = " GROUP BY sh.scheduleHeaderId, c.contractNo, pv.policyCode, pv.versionNo";
    // 값이 없는(null) 조건은 필터에서 제외한다. 계약번호는 기존처럼 부분 일치(LIKE '%값%')다.
    // 문자열 null 파라미터는 PostgreSQL에 bytea로 전달돼 LIKE가 실패하므로 명시적으로 형변환한다.
    private static final String SEARCH_WHERE = """
             WHERE (CAST(:contractNo AS String) IS NULL
                    OR c.contractNo LIKE CONCAT('%', CAST(:contractNo AS String), '%'))
               AND (:stage IS NULL OR sh.paymentStage = :stage)
               AND (:regime IS NULL OR sh.scheduleRegime = :regime)
               AND (:purpose IS NULL OR sh.schedulePurpose = :purpose)
               AND (:status IS NULL OR sh.status = :status)
            """;
    private static final String SEARCH_QUERY = HEADER_SELECT_SIMPLE + LINE_JOIN + SEARCH_WHERE
            + GROUP_BY + " ORDER BY sh.scheduleHeaderId DESC";
    private static final String COUNT_QUERY = """
            SELECT COUNT(sh)
              FROM ScheduleHeader sh
              JOIN InsuranceContract c ON c.contractId = sh.contractId
            """ + SEARCH_WHERE;
    private static final String VERSIONS_QUERY = HEADER_SELECT_SIMPLE + LINE_JOIN + """
             WHERE EXISTS (SELECT 1 FROM ScheduleHeader t
                            WHERE t.scheduleHeaderId = :scheduleHeaderId
                              AND t.contractId = sh.contractId
                              AND t.paymentStage = sh.paymentStage)
            """ + GROUP_BY + " ORDER BY sh.scheduleVersionNo DESC, sh.scheduleHeaderId DESC";
    private static final String DETAIL_QUERY = HEADER_SELECT_DETAIL + LINE_JOIN
            + " WHERE sh.scheduleHeaderId = :scheduleHeaderId" + GROUP_BY + ", i.insurerName, p.productName";
    private static final String ACTIVE_DETAIL_QUERY = HEADER_SELECT_SIMPLE + LINE_JOIN + """
             WHERE sh.contractId = :contractId
               AND sh.paymentStage = :paymentStage
               AND sh.schedulePurpose = :operational
               AND sh.activeYn = TRUE
            """ + GROUP_BY;

    public List<ScheduleHeaderResponse> selectByCondition(ScheduleSearchCondition condition, int size, long offset) {
        // ponytail: JPA의 시작 위치는 int다. 21억 행을 넘는 offset은 기존처럼 빈 페이지가 되도록 조회를 생략한다.
        if (offset > Integer.MAX_VALUE) {
            return List.of();
        }
        return headerQuery(condition)
                .setFirstResult((int) offset)
                .setMaxResults(size)
                .getResultList().stream().map(ScheduleQueryRepository::toHeader).toList();
    }

    public List<ScheduleHeaderResponse> selectAllByCondition(ScheduleSearchCondition condition) {
        return headerQuery(condition).getResultList().stream().map(ScheduleQueryRepository::toHeader).toList();
    }

    public long countByCondition(ScheduleSearchCondition condition) {
        return bindSearch(entityManager.createQuery(COUNT_QUERY, Long.class), condition).getSingleResult();
    }

    /** 대상 헤더와 같은 계약·지급단계의 모든 버전(용도 무관)을 최신 버전부터 조회한다. */
    public List<ScheduleHeaderResponse> selectVersionsByScheduleHeaderId(Long scheduleHeaderId) {
        return entityManager.createQuery(VERSIONS_QUERY, Object[].class)
                .setParameter("scheduleHeaderId", scheduleHeaderId)
                .getResultList().stream().map(ScheduleQueryRepository::toHeader).toList();
    }

    /** 헤더(보험사·상품 포함) 1건과 회차를 줄 번호 순으로 조회한다. 헤더가 없으면 null. */
    public ScheduleDetailResponse selectScheduleDetailById(Long scheduleHeaderId) {
        List<Object[]> rows = entityManager.createQuery(DETAIL_QUERY, Object[].class)
                .setParameter("scheduleHeaderId", scheduleHeaderId)
                .getResultList();
        return rows.isEmpty() ? null : toDetail(toHeader(rows.getFirst()));
    }

    /** 계약·지급단계의 활성 운영 스케줄 상세. 없으면 null. 보험사·상품명은 기존처럼 채우지 않는다. */
    public ScheduleDetailResponse selectByContractIdAndPaymentStage(Long contractId, PaymentStage paymentStage) {
        List<Object[]> rows = entityManager.createQuery(ACTIVE_DETAIL_QUERY, Object[].class)
                .setParameter("contractId", contractId)
                .setParameter("paymentStage", paymentStage)
                .setParameter("operational", SchedulePurpose.OPERATIONAL)
                .getResultList();
        return rows.isEmpty() ? null : toDetail(toHeader(rows.getFirst()));
    }

    public ScheduleHeaderInsertDTO selectScheduleHeaderById(Long scheduleId) {
        List<Object[]> rows = entityManager.createQuery("""
                SELECT sh.scheduleHeaderId, sh.contractId, sh.paymentStage, sh.policyVersionId,
                       sh.scheduleVersionNo, sh.schedulePurpose, sh.scenarioCode, sh.scheduleRegime,
                       sh.status, sh.activeYn, sh.generationReason, sh.regeneratedFromId
                  FROM ScheduleHeader sh
                 WHERE sh.scheduleHeaderId = :scheduleId
                """, Object[].class)
                .setParameter("scheduleId", scheduleId)
                .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = rows.getFirst();
        return ScheduleHeaderInsertDTO.builder()
                .scheduleHeaderId((Long) row[0])
                .contractId((Long) row[1])
                .paymentStage((PaymentStage) row[2])
                .policyVersionId((Long) row[3])
                .scheduleVersionNo((Integer) row[4])
                .schedulePurpose((SchedulePurpose) row[5])
                .scenarioCode((String) row[6])
                .scheduleRegime((ScheduleRegime) row[7])
                .status((ScheduleHeaderStatus) row[8])
                .activeYn((Boolean) row[9])
                .generationReason((String) row[10])
                .regeneratedFromId((Long) row[11])
                .build();
    }

    public List<ScheduleLineInsertDTO> selectScheduleLinesByScheduleId(Long scheduleId) {
        return entityManager.createQuery("""
                SELECT sl.scheduleLineId, sl.scheduleHeaderId, sl.lineNo, sl.installmentNo, sl.contractMonthNo,
                       sl.dueDate, sl.commissionItemId, sl.beneficiaryAgentId, sl.basisCode, sl.basisAmount,
                       sl.calculationType, sl.ratePct, sl.fixedAmount, sl.roundingScale, sl.roundingMode,
                       sl.expectedAmount, sl.paymentConditionCode, sl.lineStatus, sl.sourceCommissionRuleId
                  FROM ScheduleLine sl
                 WHERE sl.scheduleHeaderId = :scheduleId
                 ORDER BY sl.lineNo
                """, Object[].class)
                .setParameter("scheduleId", scheduleId)
                .getResultList().stream()
                .map(row -> ScheduleLineInsertDTO.builder()
                        .scheduleLineId((Long) row[0])
                        .scheduleHeaderId((Long) row[1])
                        .lineNo((Integer) row[2])
                        .installmentNo((Integer) row[3])
                        .contractMonthNo((Integer) row[4])
                        .dueDate((LocalDate) row[5])
                        .commissionItemId((Long) row[6])
                        .beneficiaryAgentId((Long) row[7])
                        .basisCode((String) row[8])
                        .basisAmount((BigDecimal) row[9])
                        .calculationType((CalculationType) row[10])
                        .ratePct((BigDecimal) row[11])
                        .fixedAmount((BigDecimal) row[12])
                        .roundingScale((Integer) row[13])
                        .roundingMode((RoundingMode) row[14])
                        .expectedAmount((BigDecimal) row[15])
                        .paymentConditionCode((String) row[16])
                        .lineStatus((ScheduleLineStatus) row[17])
                        .sourceCommissionRuleId((Long) row[18])
                        .build())
                .toList();
    }

    /** 현재 활성 운영 스케줄에 적용된 정책 버전 ID. 없으면 null. */
    public Long selectActiveOperationalPolicyVersionId(Long contractId, PaymentStage paymentStage) {
        return entityManager.createQuery("""
                SELECT sh.policyVersionId FROM ScheduleHeader sh
                 WHERE sh.contractId = :contractId
                   AND sh.paymentStage = :paymentStage
                   AND sh.schedulePurpose = :operational
                   AND sh.activeYn = TRUE
                """, Long.class)
                .setParameter("contractId", contractId)
                .setParameter("paymentStage", paymentStage)
                .setParameter("operational", SchedulePurpose.OPERATIONAL)
                .getResultList().stream().findFirst().orElse(null);
    }

    public int selectNextScheduleVersionNo(Long contractId, PaymentStage paymentStage) {
        Integer maxVersionNo = entityManager.createQuery("""
                SELECT MAX(sh.scheduleVersionNo) FROM ScheduleHeader sh
                 WHERE sh.contractId = :contractId
                   AND sh.paymentStage = :paymentStage
                   AND sh.schedulePurpose = :operational
                """, Integer.class)
                .setParameter("contractId", contractId)
                .setParameter("paymentStage", paymentStage)
                .setParameter("operational", SchedulePurpose.OPERATIONAL)
                .getSingleResult();
        return (maxVersionNo == null ? 0 : maxVersionNo) + 1;
    }

    /** 계약 수정 시 새 버전으로 교체할 활성 운영 스케줄 ID. 지급단계 코드 순서다. */
    public List<Long> selectActiveOperationalScheduleIds(Long contractId) {
        return entityManager.createQuery("""
                SELECT sh.scheduleHeaderId FROM ScheduleHeader sh
                 WHERE sh.contractId = :contractId
                   AND sh.schedulePurpose = :operational
                   AND sh.activeYn = TRUE
                 ORDER BY sh.paymentStage
                """, Long.class)
                .setParameter("contractId", contractId)
                .setParameter("operational", SchedulePurpose.OPERATIONAL)
                .getResultList();
    }

    private TypedQuery<Object[]> headerQuery(ScheduleSearchCondition condition) {
        return bindSearch(entityManager.createQuery(SEARCH_QUERY, Object[].class), condition);
    }

    private static <T> TypedQuery<T> bindSearch(TypedQuery<T> query, ScheduleSearchCondition condition) {
        String contractNo = condition.getContractNo();
        // 기존처럼 빈 문자열은 조건 없음으로 본다.
        return query.setParameter("contractNo", contractNo == null || contractNo.isEmpty() ? null : contractNo)
                .setParameter("stage", condition.getStage())
                .setParameter("regime", condition.getRegime())
                .setParameter("purpose", condition.getPurpose())
                .setParameter("status", condition.getStatus());
    }

    private static ScheduleHeaderResponse toHeader(Object[] row) {
        BigDecimal expectedTotal = (BigDecimal) row[13];
        return ScheduleHeaderResponse.builder()
                .scheduleHeaderId((Long) row[0])
                .contractNo((String) row[1])
                .insurerName((String) row[2])
                .productName((String) row[3])
                .paymentStage((PaymentStage) row[4])
                .scheduleRegime(row[5] == null ? null : ((ScheduleRegime) row[5]).name())
                .schedulePurpose(row[6] == null ? null : ((SchedulePurpose) row[6]).name())
                .scheduleVersionNo((Integer) row[7])
                .status((ScheduleHeaderStatus) row[8])
                .activeYn((Boolean) row[9])
                .generatedAt((OffsetDateTime) row[10])
                .generationReason((String) row[11])
                .lineCount(((Long) row[12]).intValue())
                // 기존 COALESCE(SUM(expected_amount), 0)과 같다.
                .expectedTotal(expectedTotal == null ? BigDecimal.ZERO : expectedTotal)
                // 기존 CONCAT(policy_code, ' v', version_no)처럼 NULL은 빈 문자열로 이어 붙인다.
                .policyVersionLabel(Objects.toString(row[14], "") + " v" + Objects.toString(row[15], ""))
                .build();
    }

    private ScheduleDetailResponse toDetail(ScheduleHeaderResponse header) {
        List<ScheduleLineResponse> lines = entityManager.createQuery("""
                SELECT sl.lineNo, sl.installmentNo, sl.contractMonthNo, sl.dueDate, ci.itemName, a.agentName,
                       sl.basisCode, sl.basisAmount, sl.calculationType, sl.ratePct, sl.expectedAmount,
                       sl.lineStatus, sl.sourceCommissionRuleId
                  FROM ScheduleLine sl
                  LEFT JOIN Agent a ON a.agentId = sl.beneficiaryAgentId
                  LEFT JOIN CommissionItem ci ON ci.commissionItemId = sl.commissionItemId
                 WHERE sl.scheduleHeaderId = :scheduleHeaderId
                 ORDER BY sl.lineNo
                """, Object[].class)
                .setParameter("scheduleHeaderId", header.getScheduleHeaderId())
                .getResultList().stream()
                .map(row -> ScheduleLineResponse.builder()
                        .lineNo((Integer) row[0])
                        .installmentNo((Integer) row[1])
                        .contractMonthNo((Integer) row[2])
                        .dueDate((LocalDate) row[3])
                        .commissionItemName((String) row[4])
                        .recipientName((String) row[5])
                        .basisCode((String) row[6])
                        .basisAmount((BigDecimal) row[7])
                        .calculationType(row[8] == null ? null : ((CalculationType) row[8]).name())
                        .ratePct((BigDecimal) row[9])
                        .expectedAmount((BigDecimal) row[10])
                        .lineStatus(row[11] == null ? null : ((ScheduleLineStatus) row[11]).name())
                        .ruleRef((Long) row[12])
                        .build())
                .toList();
        return ScheduleDetailResponse.builder()
                .scheduleHeaderId(header.getScheduleHeaderId())
                .header(header)
                .lines(lines)
                .build();
    }
}
