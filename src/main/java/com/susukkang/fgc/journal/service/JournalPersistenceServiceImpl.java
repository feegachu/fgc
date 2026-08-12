package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.audit.dto.AuditLogInsertRow;
import com.susukkang.fgc.audit.mapper.AuditLogMapper;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.dto.JournalAccountRow;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalHeaderInsertRow;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;
import com.susukkang.fgc.journal.dto.JournalLineDraft;
import com.susukkang.fgc.journal.dto.JournalLineInsertRow;
import com.susukkang.fgc.journal.mapper.JournalAccountMapper;
import com.susukkang.fgc.journal.mapper.JournalMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class JournalPersistenceServiceImpl implements JournalPersistenceService {

    private static final String CONSTRAINT_JOURNAL_NO = "uq_journal_no";
    private static final String CONSTRAINT_SOURCE_REVISION = "uq_journal_source_revision";
    private static final int MAX_JOURNAL_NO_RETRIES = 3;
    private static final int MAX_MONTHLY_SEQ = 9999;

    private final JournalMapper journalMapper;
    private final JournalAccountMapper journalAccountMapper;
    private final AuditLogMapper auditLogMapper;
    private final ConstraintErrorCodeResolver constraintErrorCodeResolver;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public JournalPersistenceServiceImpl(JournalMapper journalMapper,
                                          JournalAccountMapper journalAccountMapper,
                                          AuditLogMapper auditLogMapper,
                                          ConstraintErrorCodeResolver constraintErrorCodeResolver,
                                          PlatformTransactionManager transactionManager) {
        this.journalMapper = journalMapper;
        this.journalAccountMapper = journalAccountMapper;
        this.auditLogMapper = auditLogMapper;
        this.constraintErrorCodeResolver = constraintErrorCodeResolver;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public JournalHeaderRow saveDraft(JournalHeaderDraft draft, Long createdBy, String requestId) {
        // 1. 멱등 체크
        JournalHeaderRow existing = journalMapper.findBySourceKey(
                draft.getJournalType().name(), draft.getSourceEntityType(),
                draft.getSourceEntityId(), draft.getRevisionNo());
        if (existing != null) {
            return existing;
        }

        // 2. POSTED 중복 기표 선제 체크
        if (journalMapper.existsPostedForSource(draft.getJournalType().name(),
                draft.getSourceEntityType(), draft.getSourceEntityId())) {
            throw new FgcBusinessException(FgcErrorCode.LEDG_002, Map.of(
                    "journalType", draft.getJournalType(),
                    "sourceEntityType", draft.getSourceEntityType(),
                    "sourceEntityId", draft.getSourceEntityId()));
        }

        // 3. 계정과목 조회·검증
        List<Long> journalAccountIds = new ArrayList<>();
        for (JournalLineDraft line : draft.getLines()) {
            JournalAccountRow account = journalAccountMapper.findActiveByCode(
                    line.getAccountCode().name()
            );
            if (account == null) {
                throw new FgcBusinessException(FgcErrorCode.JOURNAL_001, Map.of(
                        "accountCode", line.getAccountCode()));
            }
            journalAccountIds.add(account.getJournalAccountId());
        }

        // 3-1. 차변·대변 합계 검증(FGC-FUN-046 인수조건 — "차변합계와 대변합계가
        // 일치하지 않으면 저장·확정이 거절된다") — 저장 시점부터 강제해야 한다. DB
        // 트리거(guard_journal_header_write)는 POSTED 전환 시점에만 균형을 검사하므로
        // (V1__baseline_v2_1_2.sql:1349-1356), DRAFT 저장 자체는 막지 못한다. #85
        // JournalEntryDraftService가 만든 draft는 항상 균형이지만, saveDraft는 어떤
        // JournalHeaderDraft든 받는 공개 API라 여기서도 검증해야 한다.
        BigDecimal debitTotal = draft.getLines().stream()
                .map(JournalLineDraft::getDebitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = draft.getLines().stream()
                .map(JournalLineDraft::getCreditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new FgcBusinessException(FgcErrorCode.LEDG_001,
                    Map.of("c", debitTotal.subtract(creditTotal).abs()));
        }

        // 채번 + 헤더/라인 INSERT + 감사로그는 하나의 REQUIRES_NEW 트랜잭션
        // (attemptSave)으로 묶는다. 1~3번 사전 체크와 실제 INSERT 사이에는 DB 잠금이
        // 없어 두 요청이 동시에 같은 원천으로 저장을 시도하면 둘 다 체크를 통과할 수
        // 있다 — 그 경우 uq_journal_source_revision 위반이 나는데, 에러로 죽이지 않고
        // 방금 상대가 커밋한 기존 행을 재조회해 그대로 반환한다(멱등성 유지). uq_journal_no
        // 충돌은 채번 자체를 다시 시도해야 하는 별개의 경우라 구분해서 처리한다.
        for (int attempt = 1; attempt <= MAX_JOURNAL_NO_RETRIES; attempt++) {
            try {
                return requiresNewTransactionTemplate.execute(
                        status -> attemptSave(draft, journalAccountIds, createdBy, requestId));
            } catch (DataIntegrityViolationException e) {
                if (matchesConstraint(e, CONSTRAINT_SOURCE_REVISION)) {
                    JournalHeaderRow racedWinner = journalMapper.findBySourceKey(
                            draft.getJournalType().name(), draft.getSourceEntityType(),
                            draft.getSourceEntityId(), draft.getRevisionNo());
                    if (racedWinner != null) {
                        return racedWinner;
                    }
                    throw e;
                }
                if (!matchesConstraint(e, CONSTRAINT_JOURNAL_NO) || attempt == MAX_JOURNAL_NO_RETRIES) {
                    throw e;
                }
                log.warn("journal_header journal_no 채번 충돌로 재시도합니다 (시도 {}/{})",
                        attempt, MAX_JOURNAL_NO_RETRIES);
            }
        }
        throw new IllegalStateException("unreachable: MAX_JOURNAL_NO_RETRIES 루프를 정상적으로 빠져나올 수 없다");
    }

    // 위반된 제약이 constraintName인지 판정 — ConstraintErrorCodeResolver가 PSQLException의
    // 구조화된 제약 이름을 우선 쓰고, 없을 때만(예: 합성 예외로 단위테스트할 때) 메시지
    // 부분일치로 대체한다.
    private boolean matchesConstraint(DataIntegrityViolationException e, String constraintName) {
        Optional<String> actual = constraintErrorCodeResolver.extractConstraintName(e);
        if (actual.isPresent()) {
            return constraintName.equals(actual.get());
        }
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? "" : root.getMessage().toLowerCase(Locale.ROOT);
        return message.contains(constraintName);
    }

    private JournalHeaderRow attemptSave(JournalHeaderDraft draft, List<Long> journalAccountIds,
                                          Long createdBy, String requestId) {
        // 4. journal_no 채번
        String year = String.valueOf(draft.getJournalDate().getYear());
        String month = String.format("%02d", draft.getJournalDate().getMonthValue());
        int seq = journalMapper.findNextJournalSeq(year, month);
        // String.format("%04d", seq)는 seq>=10000이어도 자르지 않고 자릿수를 그냥
        // 늘려버린다 — "4자리 일련번호" 형식이 조용히 깨지는 걸 막기 위해 상한을 명시적으로
        // 검사한다(#93 코드리뷰 반영). 월 1만 건은 정상 업무량을 크게 벗어나는 수치라
        // 도달하면 채번 정책 자체를 재검토해야 하는 운영 이슈로 본다.
        if (seq > MAX_MONTHLY_SEQ) {
            throw new IllegalStateException(
                    "journal_no 월간 일련번호 상한(" + MAX_MONTHLY_SEQ + ") 초과 — year=" + year
                            + ", month=" + month + ", seq=" + seq);
        }
        String journalNo = String.format("JV-%s-%s-%04d", year, month, seq);

        // 5. 헤더 INSERT — useGeneratedKeys라 insert(row) 호출 한 번으로 row 객체 자체에
        // journal_header_id가 채워져 돌아온다.
        JournalHeaderInsertRow headerRow = JournalHeaderInsertRow.builder()
                .journalNo(journalNo)
                .journalDate(draft.getJournalDate())
                .journalType(draft.getJournalType().name())
                .sourceEntityType(draft.getSourceEntityType())
                .sourceEntityId(draft.getSourceEntityId())
                .revisionNo(draft.getRevisionNo())
                .validationRunId(draft.getValidationRunId())
                .contractId(draft.getContractId())
                .policyVersionId(draft.getPolicyVersionId())
                .description(draft.getDescription())
                .createdBy(createdBy)
                .build();
        journalMapper.insert(headerRow);
        Long journalHeaderId = headerRow.getJournalHeaderId();

        // 6. 라인 INSERT
        List<JournalLineDraft> lines = draft.getLines();
        for (int i = 0; i < lines.size(); i++) {
            JournalLineDraft line = lines.get(i);
            JournalLineInsertRow lineRow = JournalLineInsertRow.builder()
                    .journalHeaderId(journalHeaderId)
                    .lineNo(line.getLineNo())
                    .journalAccountId(journalAccountIds.get(i))
                    .debitAmount(line.getDebitAmount())
                    .creditAmount(line.getCreditAmount())
                    .contractId(line.getContractId())
                    .agentId(line.getAgentId())
                    .paymentStage(line.getPaymentStage() == null ? null : line.getPaymentStage().name())
                    .commissionItemId(line.getCommissionItemId())
                    .memo(line.getMemo())
                    .build();
            journalMapper.insertLine(lineRow);
        }

        // 8. 감사 로그
        auditLogMapper.insert(AuditLogInsertRow.builder()
                .userId(createdBy)
                .actionCode("JOURNAL_DRAFT_SAVED")
                .entityType("JOURNAL_HEADER")
                .entityId(String.valueOf(journalHeaderId))
                .requestId(requestId)
                .reason("journalType=" + draft.getJournalType()
                        + ",sourceEntityType=" + draft.getSourceEntityType()
                        + ",sourceEntityId=" + draft.getSourceEntityId()
                        + ",validationRunId=" + draft.getValidationRunId())
                .build());

        // 9. FINALIZED / POSTED / REVERSED 불변성
        return journalMapper.findBySourceKey(draft.getJournalType().name(),
                draft.getSourceEntityType(), draft.getSourceEntityId(), draft.getRevisionNo());
    }
}
