package com.susukkang.fgc.journal.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 검증원장 상세(GET /api/v1/journals/{id}) 헤더 1행.
 * journal_header + insurance_contract(계약번호) + app_user(작성자/기표자 login_id) +
 * 자기참조 2건(원분개, 이 분개를 역분개한 후속 분개) 조인 투영.
 */
@Getter
@Setter
public class JournalDetailHeaderRow {
    private Long journalHeaderId;
    private String journalNo;
    private LocalDate journalDate;
    private String journalType;
    private String sourceEntityType;
    private String sourceEntityId;
    private Integer revisionNo;
    private Long contractId;
    private String contractNo;
    private Long validationRunId;
    private Long policyVersionId;
    private String correctionGroupKey;
    private String status;
    private String description;
    private String createdBy;
    private OffsetDateTime createdAt;
    private String postedBy;
    private OffsetDateTime postedAt;
    // 이 분개 자신이 역분개일 때 원분개를 가리킨다(journal_header.reversal_of_id).
    private Long reversalOfId;
    private String reversalOfJournalNo;
    // 이 분개가 나중에 다른 분개로 역분개됐을 때 그 후속 분개를 가리킨다(반대 방향 —
    // uq_journal_single_reversal 덕분에 원분개 하나당 역분개는 최대 1건이라 안전하게 단일값이다).
    private Long reversedByJournalHeaderId;
    private String reversedByJournalNo;
    // 2026-08-20 yslee - 원분개 재조회에서도 정정그룹의 신규 재기표 식별값 제공
    // 기존 코드: 원분개에서는 reversal_of_id 기반 역분개만 찾고 재기표 분개는 응답하지 않음
    // 문제: 정정 완료 후 화면을 다시 열면 처리이력 외에 실제 신규 분개 반영 여부를 확인할 수 없음
    // 개선: journal_correction_group을 통해 같은 그룹의 비역분개 후속 분개를 함께 투영
    private Long repostedJournalHeaderId;
    private String repostedJournalNo;
}
