package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.JournalHeaderInsertRow;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;
import com.susukkang.fgc.journal.dto.JournalLineInsertRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * TODO(#93): src/main/resources/mapper/journal/JournalMapper.xml에 아래 5개 SQL을 작성한다.
 * ValidationRunMapper.xml(findNextRunNo 등)이 같은 성격의 채번·조회 SQL을 이미 쓰고 있으니
 * 참고하면 좋다.
 */
@Mapper
public interface JournalMapper {

    /**
     * TODO(#93): journal_header 1행 INSERT.
     *   INSERT INTO fgc.journal_header
     *       (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
     *        revision_no, validation_run_id, contract_id, policy_version_id,
     *        correction_group_key, description, created_by)
     *     VALUES (...)
     * status/created_at은 컬럼 기본값에 맡기고 INSERT 절에 넣지 않는다(JournalHeaderInsertRow
     * Javadoc 참고 — guard_journal_header_write가 INSERT 시 status<>'DRAFT'를 거부한다).
     * <selectKey>(또는 useGeneratedKeys="true" keyProperty="journalHeaderId"
     * keyColumn="journal_header_id")로 생성된 journal_header_id를
     * row.journalHeaderId에 채워 돌려줘야 한다 — 바로 다음에 insertLine이 그 값을 FK로 쓴다.
     */
    int insert(JournalHeaderInsertRow row);

    /**
     * TODO(#93): journal_line 1행 INSERT.
     *   INSERT INTO fgc.journal_line
     *       (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount,
     *        contract_id, agent_id, payment_stage, commission_item_id, memo)
     *     VALUES (...)
     * 헤더와 마찬가지로 useGeneratedKeys로 journal_line_id를 채워 돌려줘도 되지만 필수는
     * 아니다(호출자가 생성된 라인 id를 바로 쓸 일이 없다면).
     */
    int insertLine(JournalLineInsertRow row);

    /**
     * TODO(#93): journal_no 채번. "JV-{yyyy}-{MM}-{4자리 일련번호}" 형식(#93 설계 결정) —
     * 같은 연-월 안에서 기존 journal_no의 마지막 4자리 숫자 중 최댓값 + 1을 구한다.
     *
     *   SELECT COALESCE(MAX(CAST(SUBSTRING(journal_no FROM 'JV-\d{4}-\d{2}-(\d+)$') AS int)), 0) + 1
     *     FROM fgc.journal_header
     *    WHERE journal_no LIKE 'JV-' || #{year} || '-' || #{month} || '-%'
     *
     * ValidationRunMapper.findNextRunNo(COALESCE(MAX(run_no),0)+1 패턴)와 같은 관례다.
     * year는 4자리 문자열("2026"), month는 2자리 zero-padded 문자열("07")로 서비스가
     * 미리 포맷해서 넘긴다(SQL에서 포맷팅하지 않는다).
     */
    Integer findNextJournalSeq(@Param("year") String year, @Param("month") String month);

    /**
     * TODO(#93): (journal_type, source_entity_type, source_entity_id, revision_no) 조합으로
     * 기존 분개 헤더를 찾는다 — uq_journal_source_revision과 정확히 같은 4개 컬럼이다.
     * 이 결과가 있으면 "이미 저장된 초안"이므로 서비스는 재삽입 대신 이 행을 그대로
     * 반환해야 한다(원천·개정번호 기반 중복 저장 방지, ValidationRunCreateServiceImpl의
     * createWithExplicitRunNo가 하는 멱등 처리와 같은 패턴).
     *   SELECT journal_header_id, journal_no, journal_date, journal_type, source_entity_type,
     *          source_entity_id, revision_no, validation_run_id, contract_id, policy_version_id,
     *          status, description, created_by, posted_by, posted_at, created_at
     *     FROM fgc.journal_header
     *    WHERE journal_type = #{journalType} AND source_entity_type = #{sourceEntityType}
     *      AND source_entity_id = #{sourceEntityId} AND revision_no = #{revisionNo}
     * (컬럼 별칭은 JournalHeaderRow의 camelCase 필드명과 맞춘다 — map-underscore-to-camel-case가
     * 켜져 있어 굳이 AS를 안 써도 되지만, 다른 Mapper들처럼 명시적으로 써도 된다.)
     */
    JournalHeaderRow findBySourceKey(@Param("journalType") String journalType,
                                      @Param("sourceEntityType") String sourceEntityType,
                                      @Param("sourceEntityId") String sourceEntityId,
                                      @Param("revisionNo") int revisionNo);

    /**
     * TODO(#93): 같은 원천(journal_type, source_entity_type, source_entity_id)에 이미
     * status='POSTED'인 분개가 있는지 확인한다 — POSTED 분개 중복 기표 방지용 사전 체크다.
     * 참고: DB에 이미 uq_journal_current_posted_source 부분 유니크 인덱스
     * (V1__baseline_v2_1_2.sql:1227-1229)가 최후 방어선으로 걸려 있으니, 이 메서드는
     * "DB 제약 위반 예외 대신 깔끔한 업무 예외를 미리 던지기 위한" 선제 체크일 뿐이다 —
     * 이 메서드가 없어도 정합성 자체는 깨지지 않는다.
     *   SELECT EXISTS (
     *       SELECT 1 FROM fgc.journal_header
     *        WHERE journal_type = #{journalType} AND source_entity_type = #{sourceEntityType}
     *          AND source_entity_id = #{sourceEntityId} AND status = 'POSTED'
     *   )
     */
    boolean existsPostedForSource(@Param("journalType") String journalType,
                                   @Param("sourceEntityType") String sourceEntityType,
                                   @Param("sourceEntityId") String sourceEntityId);
}
