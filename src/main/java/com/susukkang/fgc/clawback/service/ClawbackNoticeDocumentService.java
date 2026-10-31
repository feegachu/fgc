package com.susukkang.fgc.clawback.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse.Calculation;
import com.susukkang.fgc.clawback.dto.ClawbackNoticeDocumentRequest;
import com.susukkang.fgc.clawback.dto.ClawbackNoticeDocumentResponse;
import com.susukkang.fgc.clawback.repository.ClawbackCandidateQueryRepository;
import com.susukkang.fgc.clawback.repository.ClawbackNoticeDocumentRepository;
import com.susukkang.fgc.clawback.repository.ClawbackNoticeDocumentRepository.LatestCalculation;
import com.susukkang.fgc.clawback.repository.ClawbackNoticeDocumentRepository.NoticeHeader;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.storage.FileStorage;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.document.repository.GeneratedDocumentRepository;
import com.susukkang.fgc.document.repository.GeneratedDocumentRepository.StoredDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FUN-084 환수 통지서 발행(IF-API-73)과 이력(IF-API-86). 지정한 계산판 snapshot으로만 PDF를 만들고,
 * case 상태·통지일·확정액은 바꾸지 않는다. 원본은 #707 FileStorage에 두고 DB 롤백 시 지운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClawbackNoticeDocumentService {
    static final String DOCUMENT_KIND = "CLAWBACK_NOTICE";
    static final String SOURCE_ENTITY_TYPE = "CLAWBACK_CASE";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ClawbackNoticeDocumentRepository store;
    private final ClawbackCandidateQueryRepository candidates;
    private final GeneratedDocumentRepository documents;
    private final ClawbackNoticePdfRenderer renderer;
    private final FileStorage storage;
    private final ObjectMapper objectMapper;

    @Transactional
    public ClawbackNoticeDocumentResponse issue(long caseId, ClawbackNoticeDocumentRequest request,
                                                String idempotencyKey, long userId) {
        if (caseId < 1) throw invalid("id");
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw invalid("Idempotency-Key");
        }
        String hash = sha256((caseId + "\n" + request.calculationVersion() + "\n" + request.expectedSourceVersion())
                .getBytes(StandardCharsets.UTF_8));
        if (!store.tryLockRequest(idempotencyKey)) throw new FgcBusinessException(FgcErrorCode.IMPL_REQUEST_IN_PROGRESS);
        var prior = store.findRequest(idempotencyKey);
        if (prior.isPresent()) {
            var stored = prior.get();
            if (stored.requestedBy() != userId || stored.caseId() != caseId || !stored.requestHash().equals(hash)) {
                throw new FgcBusinessException(FgcErrorCode.IMPL_IDEMPOTENCY_CONFLICT);
            }
            return toResponse(documents.findById(stored.documentId()).orElseThrow());
        }

        var clawbackCase = store.lockCase(caseId).orElseThrow(() -> notFound(caseId));
        LatestCalculation latest = store.findLatestCalculation(caseId).orElse(null);
        String currentSourceVersion = clawbackCase.candidateId() == null ? null
                : candidates.findById(clawbackCase.candidateId()).map(d -> d.candidate().sourceVersion()).orElse(null);
        ensureFresh(request, latest, currentSourceVersion);

        Calculation calculation = decode(latest.snapshotJson());
        NoticeHeader header = store.findHeader(caseId, calculation.policyVersionId());
        String documentNo = "CLN-" + caseId;
        int version = documents.nextVersion(documentNo);
        OffsetDateTime issuedAt = OffsetDateTime.now(SEOUL).truncatedTo(ChronoUnit.MICROS);
        // 렌더링이 실패하면 여기서 예외가 나 파일도 이력도 남지 않는다.
        byte[] pdf = renderer.render(notice(documentNo, version, issuedAt, header, calculation));
        String contentHash = sha256(pdf);
        String storageKey = storeWithRollbackCleanup(pdf);
        long documentId = documents.insert(documentNo, version, DOCUMENT_KIND, SOURCE_ENTITY_TYPE, caseId,
                calculation.calculationVersion(), storageKey, contentHash, userId, issuedAt);
        store.saveRequest(idempotencyKey, userId, caseId, hash, documentId);
        return toResponse(documents.findById(documentId).orElseThrow());
    }

    /**
     * 발행 직전 신선도 검사. 낡았으면 409 FGC-IMPL-STATE_CHANGED로 멈추고 새 판을 만들지 않는다.
     *
     * @param request              화면이 본 계산판(calculationVersion)과 원천 버전(expectedSourceVersion)
     * @param latest               case의 최신 계산판. 계산한 적이 없으면 null
     * @param currentSourceVersion 지금 다시 계산한 후보의 sourceVersion. 후보가 없는 legacy case면 null
     */
    private static void ensureFresh(ClawbackNoticeDocumentRequest request, LatestCalculation latest,
                                    String currentSourceVersion) {
        // 화면이 본 판이 case의 최신 판이어야 한다. 계산 전이거나 그 사이 새 판이 쌓였으면 낡았다.
        if (latest == null || latest.versionNo() != request.calculationVersion()) {
            throw stale("calculationVersion", latest == null ? null : latest.versionNo());
        }
        // 그 판을 만든 원천이 화면이 본 원천과 같아야 한다.
        if (!latest.sourceVersion().equals(request.expectedSourceVersion())) {
            throw stale("expectedSourceVersion", latest.sourceVersion());
        }
        // 계산 뒤 지급·스케줄 등 원천이 바뀌었으면 낡은 금액으로 통지서를 만들지 않고 재계산을 요구한다.
        if (!request.expectedSourceVersion().equals(currentSourceVersion)) {
            throw stale("expectedSourceVersion", currentSourceVersion);
        }
    }

    private static FgcBusinessException stale(String field, Object current) {
        // FgcBusinessException은 params를 Map.copyOf로 복사하므로 null 값은 넣지 않는다.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", field);
        if (current != null) details.put("current", current);
        return new FgcBusinessException(FgcErrorCode.IMPL_STATE_CHANGED, field, details, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<ClawbackNoticeDocumentResponse> history(long caseId, int page, int size) {
        if (caseId < 1) throw invalid("id");
        if (page < 1) throw invalid("page");
        if (size < 1 || size > 100) throw invalid("size");
        if (!store.caseExists(caseId)) throw notFound(caseId);
        long total = documents.countBySource(DOCUMENT_KIND, SOURCE_ENTITY_TYPE, caseId);
        var content = documents.findBySource(DOCUMENT_KIND, SOURCE_ENTITY_TYPE, caseId, size, (long) (page - 1) * size)
                .stream().map(ClawbackNoticeDocumentService::toResponse).toList();
        return PageResponse.of(content, page, size, total, "version,desc");
    }

    private String storeWithRollbackCleanup(byte[] pdf) {
        String key;
        try {
            key = storage.store(pdf);
        } catch (IOException e) {
            throw new IllegalStateException("환수 통지서 PDF를 저장할 수 없습니다.", e);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) return;
                try {
                    storage.delete(key);
                } catch (IOException | RuntimeException e) {
                    // 지우지 못한 파일은 DB 키 대조 정리 대상이다(#707 연동 계약). 키만 남기고 내용은 남기지 않는다.
                    log.warn("롤백된 환수 통지서 파일 정리 실패 storageKey={}", key, e);
                }
            }
        });
        return key;
    }

    private ClawbackNoticePdfRenderer.Notice notice(String documentNo, int version, OffsetDateTime issuedAt,
                                                    NoticeHeader header, Calculation calculation) {
        Map<Long, String> itemNames = store.findItemNames();
        Map<String, Object> basis = calculation.basis() == null ? Map.of() : calculation.basis();
        var lines = calculation.lines().stream().map(line -> new ClawbackNoticePdfRenderer.Line(
                itemNames.getOrDefault(line.commissionItemId(), "항목 " + line.commissionItemId()),
                line.installmentNo(), line.basisAmount(), line.appliedRatePct(), line.clawbackAmount())).toList();
        return new ClawbackNoticePdfRenderer.Notice(documentNo, version, issuedAt, header.contractNo(),
                header.insurerName(), header.productName(), header.agentCode(), header.agentName(),
                triggerLabel(String.valueOf(basis.get("triggerType")), header.triggerStatus()),
                date(basis.get("eventDate")), date(basis.get("contractDate")),
                header.policyCode() + " v" + header.policyVersionNo() + " · " + header.policyName(),
                calculation.calculationVersion(), calculation.calculatedAt(),
                basis.get("elapsedMonths") instanceof Number n ? n.intValue() : null,
                "LINE_HALF_UP_WON".equals(basis.get("rounding")) ? "행별 원 단위 반올림(HALF_UP) 후 합산"
                        : String.valueOf(basis.get("rounding")),
                lines, calculation.amount());
    }

    private static String triggerLabel(String triggerType, String newStatus) {
        if ("ARBITRAGE".equals(triggerType)) return "차익거래 검증";
        if (newStatus == null) return triggerType;
        return switch (newStatus) {
            case "LAPSED" -> "계약 실효";
            case "TERMINATED" -> "계약 해지";
            case "CANCELLED" -> "청약 철회·취소";
            default -> "계약 상태 변경(" + newStatus + ")";
        };
    }

    private static LocalDate date(Object value) {
        return value == null ? null : LocalDate.parse(value.toString());
    }

    static ClawbackNoticeDocumentResponse toResponse(StoredDocument document) {
        return new ClawbackNoticeDocumentResponse(document.documentNo(), document.versionNo(),
                document.sourceVersionNo(), document.contentHash(), document.createdAt(), document.createdBy(),
                "/api/v1/documents/" + document.documentNo() + ".pdf?version=" + document.versionNo());
    }

    private Calculation decode(String json) {
        try {
            return objectMapper.readerFor(Calculation.class)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("환수 계산판을 읽을 수 없습니다.", e);
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private static FgcBusinessException notFound(long caseId) {
        return new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", caseId));
    }

    private static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }
}
