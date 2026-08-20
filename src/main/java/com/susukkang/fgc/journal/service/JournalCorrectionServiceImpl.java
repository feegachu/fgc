package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import com.susukkang.fgc.journal.dto.JournalAccountRow;
import com.susukkang.fgc.journal.dto.JournalCorrectionGroupInsertRow;
import com.susukkang.fgc.journal.dto.JournalCorrectionHeaderRow;
import com.susukkang.fgc.journal.dto.JournalCorrectionLineRow;
import com.susukkang.fgc.journal.dto.JournalCorrectionResult;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalHeaderInsertRow;
import com.susukkang.fgc.journal.dto.JournalLineDraft;
import com.susukkang.fgc.journal.dto.JournalLineInsertRow;
import com.susukkang.fgc.journal.dto.JournalRepostCommand;
import com.susukkang.fgc.journal.dto.JournalRepostLineCommand;
import com.susukkang.fgc.journal.dto.ReverseAndRepostJournalCommand;
import com.susukkang.fgc.journal.dto.ReverseJournalCommand;
import com.susukkang.fgc.journal.mapper.JournalAccountMapper;
import com.susukkang.fgc.journal.mapper.JournalCorrectionMapper;
import com.susukkang.fgc.journal.mapper.JournalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 설명 : FUN-047 원장 역분개·재기표와 정정그룹 저장 구현
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class JournalCorrectionServiceImpl implements JournalCorrectionService {

    private static final int REASON_MAX_LENGTH = 1000;
    private static final int EVIDENCE_MAX_LENGTH = 500;
    private static final int DESCRIPTION_MAX_LENGTH = 1000;
    private static final int MAX_MONTHLY_SEQ = 9999;

    private final JournalCorrectionMapper correctionMapper;
    private final JournalMapper journalMapper;
    private final JournalAccountMapper journalAccountMapper;
    private final AuditLogService auditLogService;

    @Override
    @Transactional
    public JournalCorrectionResult reverse(ReverseJournalCommand command) {
        return correct(command, null);
    }

    @Override
    @Transactional
    public JournalCorrectionResult reverseAndRepost(ReverseAndRepostJournalCommand command) {
        if (command == null || command.correctedDraft() == null) {
            throw validationFailure("correctedDraft");
        }
        return correct(command.reversal(), command.correctedDraft());
    }

    @Override
    @Transactional
    public JournalCorrectionResult reverseAndRepost(JournalRepostCommand command) {
        validateRepostCommand(command);
        JournalCorrectionHeaderRow original = correctionMapper.findHeaderForUpdate(
                command.journalHeaderId());
        validateOriginal(original, command.journalHeaderId());
        List<JournalCorrectionLineRow> originalLines = correctionMapper.findLines(
                command.journalHeaderId());
        JournalHeaderDraft correctedDraft = buildCorrectedDraft(
                original, originalLines, command);
        return correct(new ReverseJournalCommand(
                        command.journalHeaderId(), command.reason(), command.evidenceRef(),
                        command.requestedBy()),
                correctedDraft);
    }

    private void validateRepostCommand(JournalRepostCommand command) {
        if (command == null || command.journalHeaderId() == null
                || command.requestedBy() == null || command.journalDate() == null
                || command.description() == null || command.description().isBlank()
                || command.description().length() > DESCRIPTION_MAX_LENGTH
                || command.lines() == null || command.lines().isEmpty()) {
            throw validationFailure("correctedDraft");
        }
    }

    private JournalHeaderDraft buildCorrectedDraft(
            JournalCorrectionHeaderRow original,
            List<JournalCorrectionLineRow> originalLines,
            JournalRepostCommand command
    ) {
        if (originalLines.size() != command.lines().size()) {
            throw validationFailure("lines");
        }
        Map<Integer, JournalCorrectionLineRow> originalByLineNo = new HashMap<>();
        originalLines.forEach(line -> originalByLineNo.put(line.getLineNo(), line));
        HashSet<Integer> usedLineNumbers = new HashSet<>();
        List<JournalLineDraft> correctedLines = new ArrayList<>();

        for (JournalRepostLineCommand input : command.lines()) {
            if (input == null || input.originalLineNo() == null
                    || !usedLineNumbers.add(input.originalLineNo())) {
                throw validationFailure("lines.originalLineNo");
            }
            JournalCorrectionLineRow source = originalByLineNo.get(input.originalLineNo());
            if (source == null) {
                throw validationFailure("lines.originalLineNo");
            }
            JournalAccountCode accountCode;
            try {
                accountCode = JournalAccountCode.valueOf(input.accountCode());
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw validationFailure("lines.accountCode");
            }
            PaymentStage paymentStage = source.getPaymentStage() == null
                    ? null
                    : PaymentStage.valueOf(source.getPaymentStage());
            correctedLines.add(JournalLineDraft.builder()
                    .lineNo(source.getLineNo())
                    .accountCode(accountCode)
                    .debitAmount(input.debitAmount())
                    .creditAmount(input.creditAmount())
                    .contractId(source.getContractId())
                    .agentId(source.getAgentId())
                    .paymentStage(paymentStage)
                    .commissionItemId(source.getCommissionItemId())
                    .memo(input.lineDescription() == null
                            ? source.getMemo()
                            : input.lineDescription().trim())
                    .build());
        }
        correctedLines.sort(Comparator.comparingInt(JournalLineDraft::getLineNo));
        return JournalHeaderDraft.builder()
                .journalType(JournalType.valueOf(original.getJournalType()))
                .journalDate(command.journalDate())
                .sourceEntityType(original.getSourceEntityType())
                .sourceEntityId(original.getSourceEntityId())
                .revisionNo(original.getRevisionNo() + 1)
                .validationRunId(original.getValidationRunId())
                .contractId(original.getContractId())
                .policyVersionId(original.getPolicyVersionId())
                .description(command.description().trim())
                .lines(correctedLines)
                .build();
    }

    private JournalCorrectionResult correct(ReverseJournalCommand command,
                                              JournalHeaderDraft correctedDraft) {
        ValidatedRequest request = validateRequest(command);
        JournalCorrectionHeaderRow original = correctionMapper.findHeaderForUpdate(
                command.journalHeaderId());
        validateOriginal(original, command.journalHeaderId());

        List<JournalCorrectionLineRow> originalLines = correctionMapper.findLines(
                original.getJournalHeaderId());
        assertBalanced(originalLines);

        List<Long> correctedAccountIds = correctedDraft == null
                ? List.of()
                : validateCorrectedDraft(original, correctedDraft);

        lockNumberingMonths(original.getJournalDate(),
                correctedDraft == null ? null : correctedDraft.getJournalDate());

        String correctionGroupKey = newCorrectionGroupKey(original.getJournalHeaderId());
        correctionMapper.insertGroup(JournalCorrectionGroupInsertRow.builder()
                .correctionGroupKey(correctionGroupKey)
                .originalJournalHeaderId(original.getJournalHeaderId())
                .reason(request.reason())
                .evidenceRef(request.evidenceRef())
                .createdBy(command.requestedBy())
                .build());

        Long reversalId = insertReversal(
                original, originalLines, correctionGroupKey, request.reason(), command.requestedBy());
        if (correctionMapper.markPosted(reversalId, command.requestedBy()) != 1) {
            throw conflict(original.getJournalHeaderId());
        }
        if (correctionMapper.markReversed(original.getJournalHeaderId()) != 1) {
            throw conflict(original.getJournalHeaderId());
        }

        Long repostedId = null;
        if (correctedDraft != null) {
            repostedId = insertRepost(
                    correctedDraft, correctedAccountIds, correctionGroupKey, command.requestedBy());
            if (correctionMapper.markPosted(repostedId, command.requestedBy()) != 1) {
                throw conflict(original.getJournalHeaderId());
            }
        }

        recordAudit(original, reversalId, repostedId, correctionGroupKey,
                request, command.requestedBy(), originalLines, correctedDraft);

        return new JournalCorrectionResult(
                reversalId, original.getJournalHeaderId(), repostedId, correctionGroupKey);
    }

    private ValidatedRequest validateRequest(ReverseJournalCommand command) {
        if (command == null || command.journalHeaderId() == null) {
            throw validationFailure("journalHeaderId");
        }
        if (command.requestedBy() == null) {
            throw validationFailure("requestedBy");
        }
        String reason = normalizeRequired(command.reason(), REASON_MAX_LENGTH, "reason");
        String evidenceRef = normalizeOptional(
                command.evidenceRef(), EVIDENCE_MAX_LENGTH, "evidenceRef");
        return new ValidatedRequest(reason, evidenceRef);
    }

    private void validateOriginal(JournalCorrectionHeaderRow original, Long journalHeaderId) {
        if (original == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004,
                    Map.of("resource", "journal", "id", journalHeaderId));
        }
        if ("FINALIZED".equals(original.getValidationRunStatus())) {
            throw new FgcBusinessException(FgcErrorCode.VRUN_003,
                    Map.of("validationRunId", original.getValidationRunId()));
        }
        if (!"POSTED".equals(original.getStatus())
                || JournalType.REVERSAL.name().equals(original.getJournalType())) {
            throw conflict(journalHeaderId);
        }
    }

    private List<Long> validateCorrectedDraft(JournalCorrectionHeaderRow original,
                                               JournalHeaderDraft correctedDraft) {
        if (correctedDraft.getJournalType() == null
                || !correctedDraft.getJournalType().name().equals(original.getJournalType())
                || !Objects.equals(correctedDraft.getSourceEntityType(), original.getSourceEntityType())
                || !Objects.equals(correctedDraft.getSourceEntityId(), original.getSourceEntityId())
                || correctedDraft.getRevisionNo() != original.getRevisionNo() + 1
                || !Objects.equals(correctedDraft.getValidationRunId(), original.getValidationRunId())
                || !Objects.equals(correctedDraft.getContractId(), original.getContractId())
                || !Objects.equals(correctedDraft.getPolicyVersionId(), original.getPolicyVersionId())) {
            throw validationFailure("correctedDraft");
        }
        if (correctedDraft.getJournalDate() == null
                || correctedDraft.getLines() == null
                || correctedDraft.getLines().isEmpty()) {
            throw validationFailure("correctedDraft");
        }
        if (correctedDraft.getDescription() != null
                && correctedDraft.getDescription().length() > DESCRIPTION_MAX_LENGTH) {
            throw validationFailure("correctedDraft.description");
        }

        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;
        List<Long> accountIds = new ArrayList<>();
        for (JournalLineDraft line : correctedDraft.getLines()) {
            validateCorrectedLine(line);
            JournalAccountRow account = journalAccountMapper.findActiveByCode(
                    line.getAccountCode().name());
            if (account == null) {
                throw new FgcBusinessException(FgcErrorCode.JOURNAL_001,
                        Map.of("accountCode", line.getAccountCode()));
            }
            accountIds.add(account.getJournalAccountId());
            debitTotal = debitTotal.add(line.getDebitAmount());
            creditTotal = creditTotal.add(line.getCreditAmount());
        }
        assertBalanced(debitTotal, creditTotal);
        return accountIds;
    }

    private void validateCorrectedLine(JournalLineDraft line) {
        if (line == null || line.getLineNo() <= 0 || line.getAccountCode() == null
                || line.getDebitAmount() == null || line.getCreditAmount() == null
                || line.getDebitAmount().signum() < 0 || line.getCreditAmount().signum() < 0
                || (line.getDebitAmount().signum() > 0) == (line.getCreditAmount().signum() > 0)) {
            throw validationFailure("correctedDraft.lines");
        }
    }

    private void assertBalanced(List<JournalCorrectionLineRow> lines) {
        BigDecimal debitTotal = lines.stream()
                .map(JournalCorrectionLineRow::getDebitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = lines.stream()
                .map(JournalCorrectionLineRow::getCreditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertBalanced(debitTotal, creditTotal);
    }

    private void assertBalanced(BigDecimal debitTotal, BigDecimal creditTotal) {
        if (debitTotal.signum() <= 0 || debitTotal.compareTo(creditTotal) != 0) {
            throw new FgcBusinessException(FgcErrorCode.LEDG_001,
                    Map.of("c", debitTotal.subtract(creditTotal).abs()));
        }
    }

    private Long insertReversal(JournalCorrectionHeaderRow original,
                                List<JournalCorrectionLineRow> originalLines,
                                String correctionGroupKey,
                                String reason,
                                Long requestedBy) {
        JournalHeaderInsertRow header = JournalHeaderInsertRow.builder()
                .journalNo(nextJournalNo(original.getJournalDate()))
                .journalDate(original.getJournalDate())
                .journalType(JournalType.REVERSAL.name())
                .sourceEntityType("JOURNAL_HEADER")
                .sourceEntityId(String.valueOf(original.getJournalHeaderId()))
                .revisionNo(1)
                .validationRunId(original.getValidationRunId())
                .contractId(original.getContractId())
                .policyVersionId(original.getPolicyVersionId())
                .reversalOfId(original.getJournalHeaderId())
                .correctionGroupKey(correctionGroupKey)
                .description(reason)
                .createdBy(requestedBy)
                .build();
        journalMapper.insert(header);

        for (JournalCorrectionLineRow line : originalLines) {
            journalMapper.insertLine(JournalLineInsertRow.builder()
                    .journalHeaderId(header.getJournalHeaderId())
                    .lineNo(line.getLineNo())
                    .journalAccountId(line.getJournalAccountId())
                    .debitAmount(line.getCreditAmount())
                    .creditAmount(line.getDebitAmount())
                    .contractId(line.getContractId())
                    .agentId(line.getAgentId())
                    .paymentStage(line.getPaymentStage())
                    .commissionItemId(line.getCommissionItemId())
                    .memo(line.getMemo())
                    .build());
        }
        return header.getJournalHeaderId();
    }

    private Long insertRepost(JournalHeaderDraft draft,
                              List<Long> accountIds,
                              String correctionGroupKey,
                              Long requestedBy) {
        JournalHeaderInsertRow header = JournalHeaderInsertRow.builder()
                .journalNo(nextJournalNo(draft.getJournalDate()))
                .journalDate(draft.getJournalDate())
                .journalType(draft.getJournalType().name())
                .sourceEntityType(draft.getSourceEntityType())
                .sourceEntityId(draft.getSourceEntityId())
                .revisionNo(draft.getRevisionNo())
                .validationRunId(draft.getValidationRunId())
                .contractId(draft.getContractId())
                .policyVersionId(draft.getPolicyVersionId())
                .correctionGroupKey(correctionGroupKey)
                .description(draft.getDescription())
                .createdBy(requestedBy)
                .build();
        journalMapper.insert(header);

        for (int i = 0; i < draft.getLines().size(); i++) {
            JournalLineDraft line = draft.getLines().get(i);
            PaymentStage paymentStage = line.getPaymentStage();
            journalMapper.insertLine(JournalLineInsertRow.builder()
                    .journalHeaderId(header.getJournalHeaderId())
                    .lineNo(line.getLineNo())
                    .journalAccountId(accountIds.get(i))
                    .debitAmount(line.getDebitAmount())
                    .creditAmount(line.getCreditAmount())
                    .contractId(line.getContractId())
                    .agentId(line.getAgentId())
                    .paymentStage(paymentStage == null ? null : paymentStage.name())
                    .commissionItemId(line.getCommissionItemId())
                    .memo(line.getMemo())
                    .build());
        }
        return header.getJournalHeaderId();
    }

    private void lockNumberingMonths(LocalDate first, LocalDate second) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Stream.of(first, second)
                .filter(Objects::nonNull)
                .map(date -> "journal-no:" + YearMonth.from(date))
                .sorted(Comparator.naturalOrder())
                .forEach(keys::add);
        keys.forEach(correctionMapper::lockJournalNumbering);
    }

    private String nextJournalNo(LocalDate journalDate) {
        String year = String.valueOf(journalDate.getYear());
        String month = String.format("%02d", journalDate.getMonthValue());
        int seq = journalMapper.findNextJournalSeq(year, month);
        if (seq > MAX_MONTHLY_SEQ) {
            throw new IllegalStateException("journal_no 월간 일련번호 상한 초과");
        }
        return String.format("JV-%s-%s-%04d", year, month, seq);
    }

    private void recordAudit(JournalCorrectionHeaderRow original,
                             Long reversalId,
                             Long repostedId,
                             String correctionGroupKey,
                             ValidatedRequest request,
                             Long requestedBy,
                             List<JournalCorrectionLineRow> originalLines,
                             JournalHeaderDraft correctedDraft) {
        AmountTotals beforeTotals = totalsOfOriginal(originalLines);
        AmountTotals afterTotals = correctedDraft == null
                ? null
                : totalsOfCorrected(correctedDraft.getLines());
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode(repostedId == null
                        ? "JOURNAL_REVERSED"
                        : "JOURNAL_REVERSED_AND_REPOSTED")
                .entityType("JOURNAL_HEADER")
                .entityId(String.valueOf(original.getJournalHeaderId()))
                .userId(requestedBy)
                .before(new CorrectionBeforeSnapshot(
                        original.getJournalHeaderId(), original.getStatus(), original.getRevisionNo(),
                        beforeTotals.debitTotal(), beforeTotals.creditTotal()))
                .after(new CorrectionAfterSnapshot(
                        reversalId, repostedId, correctionGroupKey, request.evidenceRef(),
                        afterTotals == null ? null : afterTotals.debitTotal(),
                        afterTotals == null ? null : afterTotals.creditTotal()))
                .reason(request.reason())
                .policyVersionId(original.getPolicyVersionId())
                .build());
    }

    private static AmountTotals totalsOfOriginal(List<JournalCorrectionLineRow> lines) {
        return new AmountTotals(
                lines.stream().map(JournalCorrectionLineRow::getDebitAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                lines.stream().map(JournalCorrectionLineRow::getCreditAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static AmountTotals totalsOfCorrected(List<JournalLineDraft> lines) {
        return new AmountTotals(
                lines.stream().map(JournalLineDraft::getDebitAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                lines.stream().map(JournalLineDraft::getCreditAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static String newCorrectionGroupKey(Long originalJournalHeaderId) {
        return "JCG-" + originalJournalHeaderId + "-"
                + UUID.randomUUID().toString().replace("-", "");
    }

    private static String normalizeRequired(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw validationFailure(field);
        }
        return value.trim();
    }

    private static String normalizeOptional(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw validationFailure(field);
        }
        return value.trim();
    }

    private static FgcBusinessException validationFailure(String field) {
        return new FgcBusinessException(
                FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }

    private static FgcBusinessException conflict(Long journalHeaderId) {
        return new FgcBusinessException(FgcErrorCode.LEDG_002,
                Map.of("journalHeaderId", journalHeaderId));
    }

    private record ValidatedRequest(String reason, String evidenceRef) {
    }

    private record AmountTotals(BigDecimal debitTotal, BigDecimal creditTotal) {
    }

    private record CorrectionBeforeSnapshot(Long journalHeaderId,
                                            String status,
                                            Integer revisionNo,
                                            BigDecimal debitTotal,
                                            BigDecimal creditTotal) {
    }

    private record CorrectionAfterSnapshot(Long reversalJournalHeaderId,
                                           Long repostedJournalHeaderId,
                                           String correctionGroupKey,
                                           String evidenceRef,
                                           BigDecimal debitTotal,
                                           BigDecimal creditTotal) {
    }
}
