package com.susukkang.fgc.policy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.policy.dto.PolicyActionRequest;
import com.susukkang.fgc.policy.dto.PolicyCloneRequest;
import com.susukkang.fgc.policy.dto.PolicyDraft;
import com.susukkang.fgc.policy.dto.PolicyWriteResult;
import com.susukkang.fgc.policy.entity.PolicyVersion;
import com.susukkang.fgc.policy.repository.PolicyVersionRepository;
import com.susukkang.fgc.policy.repository.PolicyWriteRepository;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** FUN-017 정책 작성·승인. 계산 정책 선택과 조회는 기존 서비스를 유지한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class PolicyWriteService {
    private static final Set<String> WRITERS = Set.of(Roles.SETTLEMENT, Roles.GA_ADMIN, Roles.SYSTEM_ADMIN);
    private static final Set<String> APPROVERS = Set.of(Roles.GA_ADMIN, Roles.SYSTEM_ADMIN);
    private static final Set<PolicyType> WRITABLE_TYPES = Set.of(PolicyType.CURRENT_COMMISSION,
            PolicyType.FOUR_YEAR_COMMISSION, PolicyType.SEVEN_YEAR_COMMISSION,
            PolicyType.SCHEDULE_ELIGIBILITY, PolicyType.ARBITRAGE, PolicyType.RECONCILIATION_TOLERANCE);
    private static final Pattern REFERENCE = Pattern.compile("^(REG-\\d{2}[A-Z]?|SRC-\\d{3})(?:\\s++\\S.*)?$");
    private static final String ACTION_CLONE = "CLONE";
    private static final String FIELD_POLICY_TYPE = "policyType";
    private final PolicyVersionRepository policyVersionRepository;
    private final PolicyWriteRepository writeRepository;
    private final PolicyDraftValidator draftValidator;
    private final PolicyOfferingScopeValidator offeringScopeValidator;
    private final PolicySuccessionValidator successionValidator;
    private final Validator validator;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock policyClock;

    public PolicyWriteResult createDraft(PolicyDraft draft, Long userId, String idempotencyKey) {
        FgcUserDetails actor = requireActor(userId, WRITERS);
        return executeOnce(idempotencyKey, userId, "CREATE", draft, () -> {
            draftValidator.validate(draft);
            if (draft.expectedUpdatedAt() != null || hasChildIds(draft)) {
                throw invalid("expectedUpdatedAt", "최초 생성에는 기존 버전·자식 식별자를 지정할 수 없습니다.");
            }
            validateReferences(draft);
            writeRepository.lockPolicyCode(draft.policyCode());
            if (writeRepository.nextVersion(draft.policyCode()) != 1) {
                throw stateConflict("이미 존재하는 정책 코드는 새 버전 복제 API를 사용하세요.");
            }
            PolicyVersion policy = policyVersionRepository.saveAndFlush(PolicyVersion.draft(draft, 1, userId));
            writeRepository.replaceChildren(policy.getPolicyVersionId(), draft);
            writeRepository.refresh(policy);
            return recordChange("CREATE", policy, actor, null, null);
        });
    }

    public PolicyWriteResult updateDraft(Long id, PolicyDraft draft, Long userId, String idempotencyKey) {
        FgcUserDetails actor = requireActor(userId, WRITERS);
        return executeOnce(idempotencyKey, userId, "UPDATE:" + id, draft, () -> {
            draftValidator.validate(draft);
            PolicyVersion policy = lockPolicy(id);
            checkExpected(policy, PolicyStatus.DRAFT, draft.expectedUpdatedAt());
            if (!policy.getPolicyCode().equals(draft.policyCode())) {
                throw invalid("policyCode", "정책 코드는 최초 생성 이후 변경할 수 없습니다.");
            }
            if (!writeRepository.verifyChildOwnership(id, draft)) {
                throw invalid("commissionRules", "자식 규칙은 이 정책에 속한 ID만 수정할 수 있습니다.");
            }
            if (writeRepository.hasUnsupportedChildren(id)) {
                throw invalid(FIELD_POLICY_TYPE, "전용 편집 계약이 없는 기존 규칙을 덮어쓸 수 없습니다.");
            }
            validateReferences(draft, storedReferences(writeRepository.readDraft(policy)));
            Map<String, Object> before = snapshot(policy, actor);
            writeRepository.updateDraft(policy, draft);
            return recordChange("UPDATE", policy, actor, before, null);
        });
    }

    public PolicyWriteResult cloneVersion(Long id, PolicyCloneRequest request, Long userId, String idempotencyKey) {
        FgcUserDetails actor = requireActor(userId, WRITERS);
        return executeOnce(idempotencyKey, userId, "CLONE:" + id, request, () -> {
            validate(request);
            if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
                throw invalid("effectiveTo", "종료일은 시작일보다 빠를 수 없습니다.");
            }
            PolicyVersion source = lockPolicy(id);
            if (!Set.of(PolicyStatus.APPROVED, PolicyStatus.ACTIVE, PolicyStatus.RETIRED).contains(source.getStatus())) {
                throw stateConflict("승인·활성·종료 버전만 새 초안으로 복제할 수 있습니다.");
            }
            if (writeRepository.hasUnsupportedChildren(id)) {
                throw invalid(FIELD_POLICY_TYPE, "이 유형의 전용 규칙 작성·복제는 아직 지원하지 않습니다.");
            }
            writeRepository.lockPolicyCode(source.getPolicyCode());
            int version = writeRepository.nextVersion(source.getPolicyCode());
            PolicyVersion copy = policyVersionRepository.saveAndFlush(source.cloneDraft(
                    request.effectiveFrom(), request.effectiveTo(), version, userId));
            writeRepository.copyChildren(id, copy.getPolicyVersionId());
            writeRepository.refresh(copy);
            return recordChange(ACTION_CLONE, copy, actor, Map.of("sourcePolicyVersionId", id), request.reason());
        });
    }

    public PolicyWriteResult executeAction(Long id, PolicyActionRequest request, Long userId, String idempotencyKey) {
        validate(request);
        if (request.evidenceRef() != null && (request.evidenceRef().isBlank()
                || request.actionType() != PolicyActionRequest.ActionType.APPROVE)) {
            throw invalid("evidenceRef", "승인 근거는 APPROVE에서만 비어 있지 않은 문서 참조로 지정하세요.");
        }
        String evidenceRef = request.evidenceRef() == null ? null : request.evidenceRef().strip();
        boolean submit = request.actionType() == PolicyActionRequest.ActionType.SUBMIT;
        FgcUserDetails actor = requireActor(userId, submit ? WRITERS : APPROVERS);
        return executeOnce(idempotencyKey, userId, "ACTION:" + id, request, () -> {
            // Cross-code activation must serialize the overlap check and state update together.
            if (request.actionType() != PolicyActionRequest.ActionType.REJECT) {
                writeRepository.lockActivationScope();
            }
            PolicyVersion policy = lockPolicy(id);
            checkExpected(policy, request.expectedStatus(), request.expectedUpdatedAt());
            Map<String, Object> before = snapshot(policy, actor);
            PolicyStatus next = nextStatus(policy, request.actionType(), userId);
            if (request.actionType() != PolicyActionRequest.ActionType.REJECT) {
                validateSubmission(writeRepository.readDraft(policy));
            }
            if (next != PolicyStatus.DRAFT) prepareSuccession(policy, next, actor, request.reason());
            policy.transition(next, next == PolicyStatus.APPROVED ? userId : null, evidenceRef);
            policyVersionRepository.saveAndFlush(policy);
            writeRepository.appendAction(id, request.actionType().name(), userId, request.reason(), evidenceRef);
            writeRepository.refresh(policy);
            return recordChange(request.actionType().name(), policy, actor, before, request.reason());
        });
    }

    private void prepareSuccession(PolicyVersion policy, PolicyStatus next, FgcUserDetails actor, String reason) {
        List<PolicyVersion> previousVersions = writeRepository.lockActiveVersions(policy.getPolicyCode());
        if (previousVersions.size() > 1) throw stateConflict("같은 코드의 ACTIVE가 여러 개이므로 승계 기준을 확정할 수 없습니다.");
        OffsetDateTime activatedAt = OffsetDateTime.now(policyClock);
        if (!previousVersions.isEmpty() && !policy.getEffectiveFrom().isAfter(activatedAt.toLocalDate())) {
            throw invalid("effectiveFrom", "새 정책은 한국시간 기준 내일 이후 계약부터 적용해야 합니다. 과거·당일 소급 교체는 허용하지 않습니다.");
        }
        PolicyDraft replacement = writeRepository.readDraft(policy);
        for (PolicyVersion previous : previousVersions) {
            if (previous.getVersionNo() >= policy.getVersionNo()) {
                throw stateConflict("현재 ACTIVE보다 높은 버전만 승계할 수 있습니다.");
            }
            PolicyDraft original = writeRepository.readDraft(previous);
            successionValidator.validate(original, replacement);
            successionValidator.validateFeeLimits(original, replacement,
                    writeRepository.readFeeLimits(previous.getPolicyVersionId()),
                    writeRepository.readFeeLimits(policy.getPolicyVersionId()));
        }
        if (next == PolicyStatus.REVIEW) return; // 작성자가 적용일·범위·한도 오류를 제출 때 보완할 수 있게 한다.
        if (next == PolicyStatus.ACTIVE) {
            for (PolicyVersion previous : previousVersions) {
                Map<String, Object> before = snapshot(previous, actor);
                previous.transition(PolicyStatus.RETIRED, null);
                policyVersionRepository.saveAndFlush(previous);
                writeRepository.recordSuccession(previous, policy, actor.getUserId(), activatedAt);
                writeRepository.appendAction(previous.getPolicyVersionId(), "RETIRE", actor.getUserId(), reason,
                        "policy:" + policy.getPolicyVersionId());
                writeRepository.refresh(previous);
                recordChange("RETIRE", previous, actor, before, reason);
            }
        }
        // APPROVE는 아직 바꾸지 않은 같은 코드의 ACTIVE를 제외한다. ACTIVATE는 종료 후 남은 집합을 검사한다.
        if (writeRepository.hasActiveOverlap(policy, next == PolicyStatus.APPROVED)) {
            throw new FgcBusinessException(FgcErrorCode.POL_002);
        }
    }

    /** UI도 같은 역할·상태·작성자 판정을 사용한다. */
    @Transactional(readOnly = true)
    public List<String> allowedActions(PolicyVersion policy) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof FgcUserDetails actor)) {
            return List.of();
        }
        return allowedActions(policy, actor);
    }

    private List<String> allowedActions(PolicyVersion policy, FgcUserDetails actor) {
        if (!WRITERS.contains(actor.getRoleCode())) {
            return List.of();
        }
        if (!WRITABLE_TYPES.contains(policy.getPolicyType())) {
            return policy.getStatus() == PolicyStatus.DRAFT ? List.of("EDIT") : List.of();
        }
        return switch (policy.getStatus()) {
            case DRAFT -> List.of("EDIT", "SUBMIT");
            case REVIEW -> reviewActions(policy, actor);
            case APPROVED -> APPROVERS.contains(actor.getRoleCode()) && canActivateByDate(policy)
                    ? List.of(ACTION_CLONE, "ACTIVATE") : List.of(ACTION_CLONE);
            case ACTIVE, RETIRED -> List.of(ACTION_CLONE);
        };
    }

    private boolean canActivateByDate(PolicyVersion policy) {
        return policy.getEffectiveFrom().isAfter(LocalDate.now(policyClock))
                || !writeRepository.hasActiveVersion(policy.getPolicyCode());
    }

    private List<String> reviewActions(PolicyVersion policy, FgcUserDetails actor) {
        if (!APPROVERS.contains(actor.getRoleCode())) return List.of();
        if (hasAuthored(policy, actor.getUserId())) return List.of("REJECT");
        return List.of("APPROVE", "REJECT");
    }

    private boolean hasAuthored(PolicyVersion policy, Long actorId) {
        return Objects.equals(policy.getCreatedBy(), actorId)
                || writeRepository.hasContributed(policy.getPolicyVersionId(), actorId);
    }

    private PolicyStatus nextStatus(PolicyVersion policy, PolicyActionRequest.ActionType action, Long actorId) {
        PolicyStatus expected = switch (action) {
            case SUBMIT -> PolicyStatus.DRAFT;
            case APPROVE, REJECT -> PolicyStatus.REVIEW;
            case ACTIVATE -> PolicyStatus.APPROVED;
        };
        if (policy.getStatus() != expected) {
            throw stateConflict("허용되지 않은 정책 상태 전이입니다.");
        }
        if (action == PolicyActionRequest.ActionType.APPROVE && hasAuthored(policy, actorId)) {
            throw new FgcBusinessException(FgcErrorCode.POL_001);
        }
        return switch (action) {
            case SUBMIT -> PolicyStatus.REVIEW;
            case APPROVE -> PolicyStatus.APPROVED;
            case REJECT -> PolicyStatus.DRAFT;
            case ACTIVATE -> PolicyStatus.ACTIVE;
        };
    }

    private void validateSubmission(PolicyDraft draft) {
        draftValidator.validateForSubmission(draft);
        offeringScopeValidator.validateForSubmission(draft);
        validateReferences(draft, storedReferences(draft));
        if (draft.regulationRefs().isEmpty() && draft.sourceRefs().isEmpty()) {
            throw invalid("sourceRefs", "제출할 정책에는 승인된 근거가 필요합니다.");
        }
        boolean complete = switch (draft.policyType()) {
            case CURRENT_COMMISSION -> !draft.commissionRules().isEmpty();
            case FOUR_YEAR_COMMISSION, SEVEN_YEAR_COMMISSION ->
                    !draft.commissionRules().isEmpty() && !draft.feeRegimeRules().isEmpty();
            case SCHEDULE_ELIGIBILITY -> parameterKeys(draft).containsAll(
                    Set.of("STATUS_MONTH_RULE", "PRORATION_RULE", "REVIVAL_BACKFILL_RULE"));
            case ARBITRAGE -> parameterKeys(draft).containsAll(Set.of("ARBITRAGE_EFFECTIVE_CONTRACT_DATE",
                    "ARBITRAGE_REFUND_ADDITION_MONTHS", "ARBITRAGE_REFUND_ADDITION_BOUNDARY_INCLUSIVE"));
            case RECONCILIATION_TOLERANCE -> parameterKeys(draft).containsAll(
                    Set.of("TOLERANCE_AMOUNT_KRW", "TOLERANCE_DAYS"));
            // 전용 편집 계약이나 환수 D-02가 없는 유형을 빈 규칙으로 활성화하지 않는다.
            default -> false;
        };
        if (!complete) {
            throw invalid(FIELD_POLICY_TYPE, "제출에 필요한 유형별 규칙이 없거나 지원되지 않는 정책 유형입니다.");
        }
    }

    private static Set<String> parameterKeys(PolicyDraft draft) {
        return draft.parameters().stream().map(PolicyDraft.ParameterDraft::key).collect(Collectors.toSet());
    }

    private void validateReferences(PolicyDraft draft) {
        validateReferences(draft, Set.of());
    }

    private void validateReferences(PolicyDraft draft, Set<String> inherited) {
        Set<String> approvedRefs = writeRepository.approvedReferences();
        Set<String> approved = approvedRefs.stream()
                .map(PolicyWriteService::referenceCode).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> legacy = inherited.stream().filter(approvedRefs::contains)
                .filter(ref -> referenceCode(ref) == null).collect(Collectors.toSet());
        for (String ref : draft.regulationRefs()) checkReference(ref, "REG-", approved, legacy, "regulationRefs");
        for (String ref : draft.sourceRefs()) checkReference(ref, "SRC-", approved, legacy, "sourceRefs");
        for (PolicyDraft.FeeRegimeRuleDraft rule : draft.feeRegimeRules()) {
            for (String ref : rule.sourceRefs()) checkReference(ref, "", approved, legacy, "feeRegimeRules.sourceRefs");
        }
        Set<String> linked = Stream.concat(draft.regulationRefs().stream(), draft.sourceRefs().stream())
                .collect(Collectors.toSet());
        for (PolicyDraft.SourceDetailDraft detail : draft.sourceDetails()) {
            if (!linked.contains(detail.ref())) {
                throw invalid("sourceDetails.ref", "상세 근거는 정책의 근거 목록에 연결되어야 합니다.");
            }
        }
    }

    /** 과거 승인본의 문서명·경로 참조는 복제 후에도 보존하되 신규 입력으로 추가하지 않는다. */
    private static Set<String> storedReferences(PolicyDraft draft) {
        return Stream.concat(Stream.concat(draft.regulationRefs().stream(), draft.sourceRefs().stream()),
                draft.feeRegimeRules().stream().flatMap(rule -> rule.sourceRefs().stream()))
                .collect(Collectors.toSet());
    }

    /** 참조 원문에서 REG/SRC 코드를 읽는다. 형식이 아니면 null. */
    static String referenceCode(String ref) {
        if (ref == null) return null;
        Matcher matcher = REFERENCE.matcher(ref);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static void checkReference(String ref, String prefix, Set<String> approved, Set<String> legacy, String field) {
        if (legacy.contains(ref)) return;
        String code = referenceCode(ref);
        if (code == null || !code.startsWith(prefix) || !approved.contains(code)) {
            throw invalid(field, "근거 코드 레지스트리와 승인 정책에서 확인할 수 없는 근거 코드입니다.");
        }
    }

    private PolicyVersion lockPolicy(Long id) {
        return writeRepository.lockPolicy(id).orElseThrow(() ->
                new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", id)));
    }

    private static void checkExpected(PolicyVersion policy, PolicyStatus expected, OffsetDateTime updatedAt) {
        if (updatedAt == null) throw invalid("expectedUpdatedAt", "조회 시점의 수정 시각이 필요합니다.");
        if (policy.getStatus() != expected || !policy.getUpdatedAt().toInstant().equals(updatedAt.toInstant())) {
            throw stateConflict("조회 이후 정책 상태 또는 내용이 바뀌었습니다.");
        }
    }

    private PolicyWriteResult recordChange(String action, PolicyVersion policy, FgcUserDetails actor,
                                     Object before, String reason) {
        PolicyWriteResult result = result(policy, actor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("document", writeRepository.readDraft(policy));
        after.put("result", result);
        after.put("approvalEvidenceRef", policy.getApprovalEvidenceRef());
        after.put("reason", reason);
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .actionCode("POLICY_" + action).entityType("POLICY_VERSION")
                .entityId(policy.getPolicyVersionId().toString()).policyVersionId(policy.getPolicyVersionId())
                .userId(actor.getUserId()).before(before)
                .after(after)
                .reason(reason).build());
        return result;
    }

    private Map<String, Object> snapshot(PolicyVersion policy, FgcUserDetails actor) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("document", writeRepository.readDraft(policy));
        before.put("result", result(policy, actor));
        before.put("approvalEvidenceRef", policy.getApprovalEvidenceRef());
        return before;
    }

    private PolicyWriteResult result(PolicyVersion policy, FgcUserDetails actor) {
        String approvedBy = policy.getApprovedBy() == null ? null
                : policyVersionRepository.findUserLogins(List.of(policy.getApprovedBy())).stream()
                .findFirst().map(PolicyVersionRepository.UserLogin::getLoginId).orElse(null);
        return new PolicyWriteResult(policy.getPolicyVersionId(), policy.getPolicyCode(), policy.getVersionNo(),
                policy.getStatus(), policy.getUpdatedAt(), approvedBy, policy.getApprovedAt(), allowedActions(policy, actor));
    }

    private PolicyWriteResult executeOnce(String key, Long userId, String operation, Object request,
                                         Supplier<PolicyWriteResult> work) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw invalid("Idempotency-Key", "멱등키는 1~100자의 영문·숫자·._:- 형식이어야 합니다.");
        }
        String hash = requestHash(request);
        if (!writeRepository.tryLockRequest(key)) {
            throw new FgcBusinessException(FgcErrorCode.IMPL_REQUEST_IN_PROGRESS);
        }
        var prior = writeRepository.findRequest(key);
        if (prior.isPresent()) {
            var stored = prior.get();
            if (!Objects.equals(stored.actorId(), userId) || !stored.operation().equals(operation)
                    || !stored.requestHash().equals(hash)) {
                throw new FgcBusinessException(FgcErrorCode.IMPL_IDEMPOTENCY_CONFLICT);
            }
            try {
                return objectMapper.readValue(stored.responseBody(), PolicyWriteResult.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("저장된 정책 요청 응답을 읽을 수 없습니다.", e);
            }
        }
        PolicyWriteResult result = work.get();
        writeRepository.saveRequest(key, userId, operation, hash, result);
        return result;
    }

    private String requestHash(Object request) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(sorted(objectMapper.valueToTree(request)));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("정책 요청을 직렬화할 수 없습니다.", e);
        }
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = objectMapper.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> copy.set(name, sorted(node.get(name))));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            node.forEach(value -> copy.add(sorted(value)));
            return copy;
        }
        return node;
    }

    private static boolean hasChildIds(PolicyDraft draft) {
        return draft.commissionRules().stream().anyMatch(rule -> rule.commissionRuleId() != null)
                || draft.feeRegimeRules().stream().anyMatch(rule -> rule.feeRegimeRulesetId() != null);
    }

    private <T> void validate(T request) {
        if (request == null) throw invalid("body", "요청 본문이 필요합니다.");
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw invalid(violations.iterator().next().getPropertyPath().toString(), "요청 값을 확인하세요.");
        }
    }

    private static FgcUserDetails requireActor(Long userId, Set<String> roles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof FgcUserDetails actor)
                || !Objects.equals(actor.getUserId(), userId) || !roles.contains(actor.getRoleCode())) {
            throw new AccessDeniedException("정책 처리 권한이 없습니다.");
        }
        return actor;
    }

    private static FgcBusinessException invalid(String field, String detail) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), detail);
    }

    private static FgcBusinessException stateConflict(String detail) {
        return new FgcBusinessException(FgcErrorCode.POL_003, null, Map.of(), detail);
    }
}
