package com.susukkang.fgc.exceptioncase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.entity.AuditLog;
import com.susukkang.fgc.audit.repository.AuditLogRepository;
import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionResponse;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionResponse;
import com.susukkang.fgc.journal.dto.JournalRepostCommand;
import com.susukkang.fgc.journal.dto.JournalRepostLineCommand;
import com.susukkang.fgc.journal.service.JournalCorrectionService;
import jakarta.persistence.EntityManager;
import org.hibernate.query.NativeQuery;
import org.mybatis.spring.SqlSessionTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * bc35de82 ExceptionCaseService 조치 알고리즘의 테스트 전용 복원본.
 * 삭제 예정 Mapper/Insert DTO만 고정 XML SqlSessionTemplate/Map으로 대체했다.
 * 최초 정정 대상 잠금과 원장 정정, 감사 저장은 기준 리비전에서도 JPA였으며 이를 유지한다.
 * 트랜잭션 시작/commit은 비교 테스트의 동일 REQUIRED TransactionTemplate이 담당한다.
 */
public final class CorrectionActionBaseline {
    private static final String MAPPER = "correction-action-baseline.";
    private final SqlSessionTemplate session;
    private final AuditLogRepository audits;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;
    private final JournalCorrectionService corrections;

    CorrectionActionBaseline(SqlSessionTemplate session, AuditLogRepository audits, ObjectMapper mapper,
                             EntityManager entityManager, JournalCorrectionService corrections) {
        this.session = session;
        this.audits = audits;
        this.mapper = mapper;
        this.entityManager = entityManager;
        this.corrections = corrections;
    }

    ExceptionActionResponse action(Long id, ExceptionActionRequest request, Long actor, String login,
                                   boolean correctionExecuted) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new FgcBusinessException(FgcErrorCode.EXCP_001);
        }
        Target target = session.selectOne(MAPPER + "findByIdForUpdate", Map.of("exceptionCaseId", id));
        if (target == null) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004, "exceptionCaseId",
                    Map.of("field", "exceptionCaseId", "id", id), null);
        }
        ExceptionStatus from = target.status();
        ExceptionActionType type = request.actionType();
        if (ExceptionType.JOURNAL_CORRECTION_REQUIRED.name().equals(target.exceptionType())
                && !correctionExecuted && !type.isJournalCorrectionGeneralAction()) {
            throw new FgcBusinessException(FgcErrorCode.EXCP_003,
                    Map.of("status", from.name(), "actionType", type.name()));
        }
        if (!type.supports(from)) {
            throw new FgcBusinessException(FgcErrorCode.EXCP_003,
                    Map.of("status", from.name(), "actionType", type.name()));
        }
        ExceptionStatus to = type.nextStatus(from);
        int seq = session.selectOne(MAPPER + "findNextActionSeq", Map.of("exceptionCaseId", id));
        OffsetDateTime at = DateUtil.nowSeoul();
        int inserted = session.insert(MAPPER + "insertAction", params("exceptionCaseId", id,
                "actionSeq", seq, "fromStatus", from, "toStatus", to, "actionType", type,
                "reason", request.reason(), "evidenceRef", request.evidenceRef(), "actionBy", actor, "actionAt", at));
        if (inserted != 1) throw new IllegalStateException("예외 처리 이력 저장에 실패했습니다.");
        Long assigned = type == ExceptionActionType.ASSIGN ? actor : target.assignedTo();
        OffsetDateTime resolved = to == ExceptionStatus.RESOLVED || to == ExceptionStatus.REJECTED ? at : null;
        int updated = session.update(MAPPER + "updateCaseAfterAction", params("exceptionCaseId", id,
                "status", to, "assignedTo", assigned, "resolvedAt", resolved));
        if (updated != 1) throw new IllegalStateException("예외 상태 변경에 실패했습니다.");
        audits.saveAndFlush(AuditLog.create(actor, "EXCEPTION_ACTION", "EXCEPTION_CASE", id.toString(),
                json(new AuditValue(from, target.assignedTo(), null, null)),
                json(new AuditValue(to, assigned, type, request.evidenceRef())),
                request.reason(), RequestIdContext.current(), null, null));
        return new ExceptionActionResponse(null, seq, from, to, type.name(), request.reason(),
                request.evidenceRef(), actor, login, at);
    }

    JournalCorrectionActionResponse correct(Long id, JournalCorrectionActionRequest request, Long actor, String login) {
        // bc35de82 JournalCorrectionExceptionRepository.findJournalCorrectionTargetForUpdate의 SQL/스칼라를 보존.
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT exception_case_id, exception_type, status, source_entity_type, source_entity_id
                  FROM fgc.exception_case
                 WHERE exception_case_id = :exceptionCaseId
                   FOR UPDATE
                """).unwrap(NativeQuery.class);
        query.setParameter("exceptionCaseId", id);
        query.addScalar("exception_case_id", Long.class);
        query.addScalar("exception_type", String.class);
        query.addScalar("status", String.class);
        query.addScalar("source_entity_type", String.class);
        query.addScalar("source_entity_id", String.class);
        CorrectionTarget target = query.setTupleTransformer((row, aliases) -> new CorrectionTarget(
                        (Long) row[0], (String) row[1], ExceptionStatus.valueOf((String) row[2]),
                        (String) row[3], (String) row[4])).getResultList().stream().findFirst().orElse(null);
        if (target == null) throw new FgcBusinessException(FgcErrorCode.COMMON_004, "exceptionCaseId",
                Map.of("field", "exceptionCaseId", "id", id), null);
        if (!ExceptionType.JOURNAL_CORRECTION_REQUIRED.name().equals(target.type())
                || !"JOURNAL_HEADER".equals(target.sourceType())) {
            throw new FgcBusinessException(FgcErrorCode.EXCP_004, Map.of("exceptionCaseId", id));
        }
        if (target.status() != ExceptionStatus.IN_REVIEW) {
            throw new FgcBusinessException(FgcErrorCode.EXCP_003,
                    Map.of("status", target.status().name(), "actionType", ExceptionActionType.CORRECT.name()));
        }
        Long original;
        try { original = Long.valueOf(target.sourceId()); }
        catch (NumberFormatException | NullPointerException exception) {
            throw new FgcBusinessException(FgcErrorCode.EXCP_004,
                    Map.of("sourceEntityId", String.valueOf(target.sourceId())));
        }
        var correction = corrections.reverseAndRepost(new JournalRepostCommand(original,
                request.reason(), request.evidenceRef(), actor, request.journalDate(), request.description(),
                request.lines().stream().map(line -> new JournalRepostLineCommand(line.originalLineNo(),
                        line.accountCode(), line.debitAmount(), line.creditAmount(), line.lineDescription())).toList()));
        var action = action(id, new ExceptionActionRequest(ExceptionActionType.CORRECT,
                request.reason(), request.evidenceRef()), actor, login, true);
        return JournalCorrectionActionResponse.from(action, correction);
    }

    private String json(AuditValue value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("예외 처리 감사값 직렬화에 실패했습니다.", exception);
        }
    }

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    public record Target(Long exceptionCaseId, String exceptionType, ExceptionStatus status, Long assignedTo) { }
    private record CorrectionTarget(Long id, String type, ExceptionStatus status, String sourceType, String sourceId) { }
    private record AuditValue(ExceptionStatus status, Long assignedTo, ExceptionActionType actionType, String evidenceRef) { }
}
