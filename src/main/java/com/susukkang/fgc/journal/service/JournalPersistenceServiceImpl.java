package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.audit.entity.AuditLog;
import com.susukkang.fgc.audit.repository.AuditLogRepository;
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

/**
 * 설명 : 분개 초안과 라인 및 감사로그를 같은 독립 트랜잭션에서 저장한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Slf4j
@Service
public class JournalPersistenceServiceImpl implements JournalPersistenceService {

    private static final String CONSTRAINT_JOURNAL_NO = "uq_journal_no";
    private static final String CONSTRAINT_SOURCE_REVISION = "uq_journal_source_revision";
    private static final int MAX_JOURNAL_NO_RETRIES = 3;
    private static final int MAX_MONTHLY_SEQ = 9999;

    private final JournalMapper journalMapper;
    private final JournalAccountMapper journalAccountMapper;
    private final AuditLogRepository auditLogRepository;
    private final ConstraintErrorCodeResolver constraintErrorCodeResolver;
    private final TransactionTemplate requiresNewTransactionTemplate;

    /**
     * 설명 : 분개 저장 의존성과 감사 Repository를 주입하고 REQUIRES_NEW 트랜잭션을 준비한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-27
     */
    public JournalPersistenceServiceImpl(JournalMapper journalMapper,
                                          JournalAccountMapper journalAccountMapper,
                                          AuditLogRepository auditLogRepository,
                                          ConstraintErrorCodeResolver constraintErrorCodeResolver,
                                          PlatformTransactionManager transactionManager) {
        this.journalMapper = journalMapper;
        this.journalAccountMapper = journalAccountMapper;
        this.auditLogRepository = auditLogRepository;
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

        // 3-1. 차변·대변 합계 검증
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

    /**
     * 설명 : 분개 번호를 채번하고 헤더·라인·감사로그를 저장하며 오류는 트랜잭션 호출자에게 전파한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-27
     */
    private JournalHeaderRow attemptSave(JournalHeaderDraft draft, List<Long> journalAccountIds,
                                          Long createdBy, String requestId) {
        // 4. journal_no 채번
        String year = String.valueOf(draft.getJournalDate().getYear());
        String month = String.format("%02d", draft.getJournalDate().getMonthValue());
        int seq = journalMapper.findNextJournalSeq(year, month);
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

        // 2026-09-27 hjKang - 분개 감사 저장을 JPA Repository로 전환한다.
        // 기존 코드: 저장 DTO를 MyBatis Mapper에 전달해 감사행을 추가했다.
        // 문제: 공용 감사 저장이 JPA로 전환된 뒤에도 분개 저장은 XML 쿼리에 의존했다.
        // 개선: 현재 REQUIRES_NEW 트랜잭션에서 엔티티를 저장·동기화해 헤더·라인과 함께 커밋하거나 롤백한다.
        String reason = "journalType=" + draft.getJournalType()
                + ",sourceEntityType=" + draft.getSourceEntityType()
                + ",sourceEntityId=" + draft.getSourceEntityId()
                + ",validationRunId=" + draft.getValidationRunId();
        AuditLog auditLog = AuditLog.create(createdBy, "JOURNAL_DRAFT_SAVED", "JOURNAL_HEADER",
                String.valueOf(journalHeaderId), null, null, reason, requestId, null, null);
        auditLogRepository.saveAndFlush(auditLog);

        // 9. FINALIZED / POSTED / REVERSED 불변성
        return journalMapper.findBySourceKey(draft.getJournalType().name(),
                draft.getSourceEntityType(), draft.getSourceEntityId(), draft.getRevisionNo());
    }
}
