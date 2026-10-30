package com.susukkang.fgc.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.AgentDataScopeService;
import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.report.dto.StatementBasis;
import com.susukkang.fgc.report.dto.StatementDetail;
import com.susukkang.fgc.report.dto.StatementEntry;
import com.susukkang.fgc.report.dto.StatementListResponse;
import com.susukkang.fgc.report.dto.StatementSummary;
import com.susukkang.fgc.report.repository.AgentStatementRepository;
import com.susukkang.fgc.report.repository.AgentStatementRepository.Filter;
import com.susukkang.fgc.report.repository.AgentStatementRepository.Row;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FUN-060 설계사 수수료 명세(IF-API-87·88). 본인 경로는 인증된 AGENT 의 연결 설계사만, 내부 경로는
 * AGENT 가 아닌 내부 역할만 쓴다. 목록·합계·상세가 같은 조회 저장소를 공유해 같은 본인 귀속 모집단을 본다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentStatementService {
    private static final Pattern ENTRY_KEY = Pattern.compile("([A-Z]+):(\\d{1,18}):(\\d{1,18})");
    private static final int MAX_CONTRACT_NO_LENGTH = 80;
    private static final Map<String, String> DATE_BASIS = Map.of(
            AgentStatementRepository.PAYMENT, "지급 귀속월",
            AgentStatementRepository.HELD, "보류 예정월",
            AgentStatementRepository.ADJUSTMENT, "조정 처리월",
            AgentStatementRepository.CLAWBACK, "환수 계산월");
    private static final List<String> WARNINGS = List.of(
            "CLAWBACK_AMOUNT_IS_CALCULATED_NOT_RECOVERED",
            "CLAWBACK_MONTH_USES_CASE_CREATED_AT",
            "ADJUSTMENT_DEDUCTION_IS_NEGATIVE");

    private final AgentStatementRepository repository;
    private final AgentDataScopeService scope;
    private final ObjectMapper objectMapper;

    public StatementListResponse listMine(String month, String contractNo, Long commissionItemId, String entryType,
                                          String sort, int page, int size) {
        return list(mineAgentId(), month, contractNo, commissionItemId, entryType, sort, page, size);
    }

    public StatementListResponse listForAgent(long agentId, String month, String contractNo, Long commissionItemId,
                                              String entryType, String sort, int page, int size) {
        return list(internalAgentId(agentId), month, contractNo, commissionItemId, entryType, sort, page, size);
    }

    public StatementDetail detailMine(String entryKey) {
        return detail(mineAgentId(), entryKey);
    }

    public StatementDetail detailForAgent(long agentId, String entryKey) {
        return detail(internalAgentId(agentId), entryKey);
    }

    private StatementListResponse list(long agentId, String month, String contractNo, Long commissionItemId,
                                       String entryType, String sort, int page, int size) {
        YearMonth yearMonth = requireMonth(month);
        if (entryType != null && !AgentStatementRepository.ENTRY_TYPES.contains(entryType)) {
            throw invalid("entryType");
        }
        if (page < 1) {
            throw invalid("page");
        }
        if (size < 1 || size > 100) {
            throw invalid("size");
        }
        String contract = contractNo == null || contractNo.isBlank() ? null : contractNo.trim();
        if (contract != null && contract.length() > MAX_CONTRACT_NO_LENGTH) {
            throw invalid("contractNo");
        }
        ListSort listSort = ListSort.parse(sort, AgentStatementRepository.SORT_KEYS, AgentStatementRepository.DEFAULT_SORT);
        Filter filter = new Filter(agentId, yearMonth.atDay(1), contract, commissionItemId, entryType);

        // 목록과 합계는 같은 필터를 쓰고, 전체 건수는 합계에서 얻어 한 모집단을 두 번 다르게 세지 않는다.
        StatementSummary summary = repository.summary(filter);
        long total = summary.payment().count() + summary.held().count()
                + summary.adjustment().count() + summary.clawback().count();
        List<StatementEntry> items = repository.search(filter, listSort, (page - 1) * size, size).stream()
                .map(AgentStatementService::entry).toList();
        return new StatementListResponse(PageResponse.of(items, page, size, total, listSort.value()), summary,
                basis(yearMonth));
    }

    private StatementDetail detail(long agentId, String entryKey) {
        Matcher key = decode(entryKey);
        String entryType = key.group(1);
        if (!AgentStatementRepository.ENTRY_TYPES.contains(entryType)) {
            throw invalid("entryKey");
        }
        // 남의 원천이거나 없는 키는 같은 404 로 답해 존재 여부가 드러나지 않게 한다.
        Row row = repository.find(agentId, entryType, Long.parseLong(key.group(2)), Long.parseLong(key.group(3)))
                .orElseThrow(() -> new FgcBusinessException(FgcErrorCode.COMMON_004));
        return new StatementDetail(entry(row), evidence(row), basis(YearMonth.from(row.basisDate())));
    }

    private long mineAgentId() {
        Long agentId = scope.currentAgentId();
        if (agentId == null) {
            throw new FgcBusinessException(FgcErrorCode.AUTH_003);
        }
        return agentId;
    }

    private long internalAgentId(long agentId) {
        if (scope.currentAgentId() != null) {
            throw new FgcBusinessException(FgcErrorCode.AUTH_003);
        }
        if (!repository.agentExists(agentId)) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_004);
        }
        return agentId;
    }

    private static YearMonth requireMonth(String month) {
        if (month == null || month.isBlank()) {
            throw invalid("month");
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException ex) {
            throw invalid("month");
        }
    }

    private static StatementBasis basis(YearMonth month) {
        return new StatementBasis(month.toString(), new StatementBasis.Range(month.atDay(1), month.atEndOfMonth()),
                DATE_BASIS, WARNINGS);
    }

    static String encodeKey(String entryType, long sourceId, long subId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((entryType + ":" + sourceId + ":" + subId).getBytes(StandardCharsets.UTF_8));
    }

    /** 사용자가 보낸 값은 숫자만 허용하는 정규식으로 검증하고, SQL 에는 파싱한 값만 바인딩한다. */
    private static Matcher decode(String entryKey) {
        try {
            String text = new String(Base64.getUrlDecoder().decode(entryKey), StandardCharsets.UTF_8);
            Matcher matcher = ENTRY_KEY.matcher(text);
            if (matcher.matches()) {
                return matcher;
            }
        } catch (IllegalArgumentException ex) {
            // 형식이 맞지 않는 키는 아래에서 같은 오류로 처리한다.
        }
        throw invalid("entryKey");
    }

    private static StatementEntry entry(Row row) {
        return new StatementEntry(encodeKey(row.entryType(), row.sourceId(), row.subId()), row.entryType(),
                row.contractId(), row.contractNo(), row.itemCode(), row.itemName(), row.installmentNo(),
                row.basisDate(), row.statusCode(), statusLabel(row.entryType(), row.statusCode()), row.amount(),
                row.policyVersionId(), new StatementEntry.Source(row.sourceEntityType(), row.sourceId()),
                row.amount() == null ? StatementEntry.MISSING : StatementEntry.AVAILABLE);
    }

    private static String statusLabel(String entryType, String statusCode) {
        return switch (entryType) {
            case AgentStatementRepository.PAYMENT -> "지급 확정";
            case AgentStatementRepository.HELD -> "지급 보류";
            case AgentStatementRepository.ADJUSTMENT -> "정정 반영";
            default -> switch (statusCode) {
                case "CALCULATED" -> "환수 계산 완료";
                case "NOTIFIED" -> "환수 통지";
                case "OBJECTED" -> "이의 접수";
                case "CONFIRMED" -> "환수 확정";
                case "RECOVERING" -> "회수 진행";
                case "CLOSED" -> "종결";
                default -> statusCode;
            };
        };
    }

    private Map<String, Object> evidence(Row row) {
        try {
            return objectMapper.readValue(row.detailJson(), new TypeReference<>() { });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }
}
