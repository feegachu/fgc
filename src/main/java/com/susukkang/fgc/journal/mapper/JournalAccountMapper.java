package com.susukkang.fgc.journal.mapper;

import com.susukkang.fgc.journal.dto.JournalAccountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * TODO(#93): src/main/resources/mapper/journal/JournalAccountMapper.xml에 아래 SQL을 작성한다.
 */
@Mapper
public interface JournalAccountMapper {

    /**
     * TODO(#93): account_code로 "활성" 계정과목 1건을 찾는다. active_yn=false거나 코드
     * 자체가 없으면 null을 반환해야 한다 — 서비스가 이 null을 "저장 불가" 업무 예외로
     * 바꿔야 한다("계정과목은 journal_account의 활성 계정과목만 참조" 요구사항).
     *
     *   SELECT journal_account_id, account_code, account_name, normal_balance, active_yn
     *     FROM fgc.journal_account
     *    WHERE account_code = #{accountCode} AND active_yn = true
     *
     * 호출부(JournalPersistenceServiceImpl)는 JournalAccountCode enum(#85)의 name()을
     * accountCode로 그대로 넘긴다 — JournalAccountCode.EXPECTED_RECEIVABLE.name() == "EXPECTED_RECEIVABLE".
     */
    JournalAccountRow findActiveByCode(@Param("accountCode") String accountCode);
}
