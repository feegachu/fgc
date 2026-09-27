package com.susukkang.fgc.contract.repository;

import com.susukkang.fgc.contract.dto.ContractJournalLineRow;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 계약 상세의 검증원장 탭에 필요한 분개 헤더·라인·계정 정보를 조회한다.
 * 원장 도메인 엔티티 없이 네이티브 SQL 결과를 계약 조회용 DTO로 반환한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContractJournalProjectionRepository {

    private final EntityManager entityManager;

    /**
     * 설명 : 계약에 연결된 모든 분개 라인을 헤더 ID·라인 번호 순으로 조회한다.
     *
     * @param contractId 보험계약 ID
     * @return 분개 라인별 조회 결과. 연결된 분개가 없으면 빈 목록
     */
    public List<ContractJournalLineRow> findJournalLinesByContractId(Long contractId) {
        // 1. 계약에 연결된 분개 헤더·라인과 계정 정보를 함께 조회
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT h.journal_header_id, h.journal_no, h.journal_date, h.journal_type,
                       h.source_entity_type, h.source_entity_id, h.revision_no,
                       h.policy_version_id, h.validation_run_id, h.status, h.description,
                       l.line_no, a.account_code, a.account_name,
                       l.debit_amount, l.credit_amount, l.agent_id, l.payment_stage,
                       l.commission_item_id, l.memo
                  FROM fgc.journal_header h
                  JOIN fgc.journal_line l ON l.journal_header_id = h.journal_header_id
                  JOIN fgc.journal_account a ON a.journal_account_id = l.journal_account_id
                 WHERE h.contract_id = :contractId
                 ORDER BY h.journal_header_id, l.line_no
                """).unwrap(NativeQuery.class);
        query.setParameter("contractId", contractId);

        // 2. DB의 숫자·날짜·문자열을 DTO 필드 타입으로 읽도록 지정
        query.addScalar("journal_header_id", Long.class);
        query.addScalar("journal_no", String.class);
        query.addScalar("journal_date", LocalDate.class);
        query.addScalar("journal_type", String.class);
        query.addScalar("source_entity_type", String.class);
        query.addScalar("source_entity_id", String.class);
        query.addScalar("revision_no", Integer.class);
        query.addScalar("policy_version_id", Long.class);
        query.addScalar("validation_run_id", Long.class);
        query.addScalar("status", String.class);
        query.addScalar("description", String.class);
        query.addScalar("line_no", Integer.class);
        query.addScalar("account_code", String.class);
        query.addScalar("account_name", String.class);
        query.addScalar("debit_amount", BigDecimal.class);
        query.addScalar("credit_amount", BigDecimal.class);
        query.addScalar("agent_id", Long.class);
        query.addScalar("payment_stage", String.class);
        query.addScalar("commission_item_id", Long.class);
        query.addScalar("memo", String.class);

        // 3. 정렬된 각 행을 DTO로 변환(헤더별 묶기와 합계 계산은 서비스에서 수행)
        return query.setTupleTransformer((row, aliases) -> toJournalLineRow(row)).getResultList();
    }

    private ContractJournalLineRow toJournalLineRow(Object[] row) {
        ContractJournalLineRow result = new ContractJournalLineRow();
        result.setJournalHeaderId((Long) row[0]);
        result.setJournalNo((String) row[1]);
        result.setJournalDate((LocalDate) row[2]);
        result.setJournalType((String) row[3]);
        result.setSourceEntityType((String) row[4]);
        result.setSourceEntityId((String) row[5]);
        result.setRevisionNo((Integer) row[6]);
        result.setPolicyVersionId((Long) row[7]);
        result.setValidationRunId((Long) row[8]);
        result.setStatus((String) row[9]);
        result.setDescription((String) row[10]);
        result.setLineNo((Integer) row[11]);
        result.setAccountCode((String) row[12]);
        result.setAccountName((String) row[13]);
        result.setDebitAmount((BigDecimal) row[14]);
        result.setCreditAmount((BigDecimal) row[15]);
        result.setAgentId((Long) row[16]);
        result.setPaymentStage((String) row[17]);
        result.setCommissionItemId((Long) row[18]);
        result.setMemo((String) row[19]);
        return result;
    }
}
