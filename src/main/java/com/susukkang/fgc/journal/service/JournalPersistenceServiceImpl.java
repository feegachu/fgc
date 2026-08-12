package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.audit.mapper.AuditLogMapper;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;
import com.susukkang.fgc.journal.mapper.JournalAccountMapper;
import com.susukkang.fgc.journal.mapper.JournalMapper;
import org.springframework.stereotype.Service;

/**
 * TODO(#93): saveDraft()를 아래 순서대로 구현한다.
 *
 *   1. 멱등 체크: journalMapper.findBySourceKey(draft.getJournalType().name(),
 *      draft.getSourceEntityType(), draft.getSourceEntityId(), draft.getRevisionNo())로
 *      기존 행을 찾는다. 있으면 그대로 반환하고 끝 — 재삽입하지 않는다("원천·개정번호
 *      기반 중복 저장 방지" 요구사항, ValidationRunCreateServiceImpl.createWithExplicitRunNo와
 *      같은 패턴).
 *
 *   2. POSTED 중복 기표 선제 체크(선택): journalMapper.existsPostedForSource(...)가 true면
 *      업무 예외(FgcBusinessException 계열, 새 에러코드 필요 — FgcErrorCode에 JOURNAL_xxx
 *      추가)를 던진다. 이 이슈에서는 항상 DRAFT로만 저장하므로 이론상 거의 안 걸리지만,
 *      같은 원천으로 서로 다른 revision_no draft가 실수로 여러 번 만들어지는 상황을
 *      조기에 막아준다.
 *
 *   3. 계정과목 조회·검증: draft.getLines()의 각 줄마다
 *      journalAccountMapper.findActiveByCode(line.getAccountCode().name())을 호출한다.
 *      null이면(코드가 없거나 active_yn=false) 업무 예외를 던진다("활성 계정과목만
 *      참조" 요구사항) — journal_account가 아직 시드되지 않은 환경이라면 이 체크가 항상
 *      실패할 것이므로, 로컬 검증 시 시드 데이터가 있는지 먼저 확인한다.
 *
 *   4. journal_no 채번: journalDate에서 year("2026"), month("07", zero-padded 2자리)를
 *      뽑아 journalMapper.findNextJournalSeq(year, month)를 호출하고,
 *      String.format("JV-%s-%s-%04d", year, month, seq)로 조합한다(#93 설계 결정 형식).
 *      uq_journal_no UNIQUE 제약과 충돌할 수 있으니, ValidationRunCreateServiceImpl처럼
 *      DataIntegrityViolationException을 잡아 REQUIRES_NEW 트랜잭션으로 재시도하는 방식을
 *      쓰는 걸 권장한다(같은 트랜잭션 안에서 재시도하면 PostgreSQL이 트랜잭션을 이미
 *      abort 상태로 만들어 재시도 INSERT조차 거부한다).
 *
 *   5. 헤더 INSERT: JournalHeaderInsertRow를 draft 값 그대로 채우고(journalNo는 4번 결과,
 *      createdBy는 파라미터) journalMapper.insert(row)를 호출한다. useGeneratedKeys로
 *      row.getJournalHeaderId()가 채워진다.
 *
 *   6. 라인 INSERT: draft.getLines()를 순회하며 JournalLineInsertRow를 만들고(journalHeaderId는
 *      5번 결과, journalAccountId는 3번에서 조회한 실제 FK 값 — line.getAccountCode()가
 *      아니라 JournalAccountRow.getJournalAccountId()를 넣어야 한다) journalMapper.insertLine을
 *      각 줄마다 호출한다.
 *
 *   7. 트랜잭션: 5·6번(헤더+모든 라인)은 반드시 하나의 트랜잭션이어야 한다 — 이 클래스
 *      메서드 전체에 @Transactional을 붙이면 된다(ValidationRunBatchLifecycleServiceImpl
 *      참고). 4번의 재시도 트랜잭션(REQUIRES_NEW)만 별도로 분리해야 한다 — 채번 충돌
 *      재시도가 헤더·라인 INSERT까지 통째로 롤백시키면 안 되기 때문이다. 즉 saveDraft() 안에서
 *      "채번"과 "헤더+라인 INSERT"를 서로 다른 트랜잭션 경계로 나누는 구조가 된다.
 *
 *   8. 감사 로그: 5·6번이 성공한 뒤 AuditLogMapper.insert(AuditLogInsertRow.builder()...)를
 *      직접 호출한다 — actionCode="JOURNAL_HEADER_POSTED"(또는 draft 상태가 항상 DRAFT이므로
 *      "JOURNAL_DRAFT_SAVED" 쪽이 더 정확할 수 있다, 판단 필요), entityType="JOURNAL_HEADER",
 *      entityId=String.valueOf(journalHeaderId), userId=createdBy. "저장 성공 시 원천·분개
 *      ID·검증 실행 ID·요청 ID를 포함한 감사 로그" 요구사항이 reason 필드에 원천 정보를,
 *      requestId는 이 서비스의 호출자가 파라미터로 내려줘야 한다(현재 saveDraft 시그니처엔
 *      requestId가 없다 — 필요하면 시그니처에 추가할지 먼저 결정한다).
 *
 *   9. FINALIZED 불변성: 이미 guard_journal_header_write 트리거가 "FINALIZED validation_run에
 *      분개를 추가할 수 없다"를 막는다(V1__baseline_v2_1_2.sql:1300-1305). 이 서비스가
 *      직접 검사할 필요는 없다 — draft.getValidationRunId()가 FINALIZED 실행을 가리키면
 *      5번 INSERT 시점에 트리거가 DataAccessException을 던지고, 그게 그대로 위로
 *      전파되게 두면 된다. POSTED/REVERSED 불변성도 마찬가지로 이 이슈(INSERT만 함)에서는
 *      해당 사항이 없다 — UPDATE/DELETE를 만드는 후속 이슈(정정·POSTED 전이)의 몫이다.
 */
@Service
public class JournalPersistenceServiceImpl implements JournalPersistenceService {

    private final JournalMapper journalMapper;
    private final JournalAccountMapper journalAccountMapper;
    private final AuditLogMapper auditLogMapper;

    public JournalPersistenceServiceImpl(JournalMapper journalMapper,
                                          JournalAccountMapper journalAccountMapper,
                                          AuditLogMapper auditLogMapper) {
        this.journalMapper = journalMapper;
        this.journalAccountMapper = journalAccountMapper;
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public JournalHeaderRow saveDraft(JournalHeaderDraft draft, Long createdBy) {
        throw new UnsupportedOperationException("TODO(#93): 위 클래스 Javadoc의 1~9단계대로 구현");
    }
}
