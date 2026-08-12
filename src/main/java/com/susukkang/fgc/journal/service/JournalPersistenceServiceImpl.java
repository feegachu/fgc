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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class JournalPersistenceServiceImpl implements JournalPersistenceService {

    private static final String CONSTRAINT_JOURNAL_NO = "uq_journal_no";
    private static final int MAX_JOURNAL_NO_RETRIES = 3;

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

        // 채번 + 헤더/라인 INSERT + 감사로그는 하나의 REQUIRES_NEW 트랜잭션
        // (attemptSave)으로 묶고, uq_journal_no 충돌일 때만 그 트랜잭션 전체를 다시 시도
        for (int attempt = 1; attempt <= MAX_JOURNAL_NO_RETRIES; attempt++) {
            try {
                return requiresNewTransactionTemplate.execute(
                        status -> attemptSave(draft, journalAccountIds, createdBy, requestId));
            } catch (DataIntegrityViolationException e) {
                if (!isJournalNoCollision(e) || attempt == MAX_JOURNAL_NO_RETRIES) {
                    throw e;
                }
                log.warn("journal_header journal_no 채번 충돌로 재시도합니다 (시도 {}/{})",
                        attempt, MAX_JOURNAL_NO_RETRIES);
            }
        }
        throw new IllegalStateException("unreachable: MAX_JOURNAL_NO_RETRIES 루프를 정상적으로 빠져나올 수 없다");
    }

    // 위반된 제약이 uq_journal_no(채번 충돌)인지 판정
    private boolean isJournalNoCollision(DataIntegrityViolationException e) {
        Optional<String> constraintName = constraintErrorCodeResolver.extractConstraintName(e);
        if (constraintName.isPresent()) {
            return CONSTRAINT_JOURNAL_NO.equals(constraintName.get());
        }
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? "" : root.getMessage().toLowerCase(Locale.ROOT);
        return message.contains(CONSTRAINT_JOURNAL_NO);
    }

    private JournalHeaderRow attemptSave(JournalHeaderDraft draft, List<Long> journalAccountIds,
                                          Long createdBy, String requestId) {
        // 4. journal_no 채번
        String year = String.valueOf(draft.getJournalDate().getYear());
        String month = String.format("%02d", draft.getJournalDate().getMonthValue());
        int seq = journalMapper.findNextJournalSeq(year, month);
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
