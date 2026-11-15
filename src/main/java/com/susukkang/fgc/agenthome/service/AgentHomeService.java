package com.susukkang.fgc.agenthome.service;

import com.susukkang.fgc.agenthome.dto.AgentHomeResponse;
import com.susukkang.fgc.agenthome.dto.AgentHomeResponse.Basis;
import com.susukkang.fgc.agenthome.dto.AgentHomeResponse.Bucket;
import com.susukkang.fgc.agenthome.dto.AgentHomeResponse.CodeCount;
import com.susukkang.fgc.agenthome.dto.AgentHomeResponse.Filters;
import com.susukkang.fgc.agenthome.dto.AgentHomeResponse.InsurerCount;
import com.susukkang.fgc.agenthome.dto.AgentHomeResponse.LastCheck;
import com.susukkang.fgc.agenthome.dto.AgentHomeResponse.MonthlyCount;
import com.susukkang.fgc.agenthome.dto.AgentHomeResponse.MyCommission;
import com.susukkang.fgc.agenthome.dto.AgentHomeResponse.Portfolio;
import com.susukkang.fgc.agenthome.dto.AgentHomeResponse.PriorityContract;
import com.susukkang.fgc.agenthome.dto.AgentHomeResponse.Range;
import com.susukkang.fgc.agenthome.dto.ContractCheckRequest;
import com.susukkang.fgc.agenthome.dto.ContractCheckResponse;
import com.susukkang.fgc.agenthome.repository.AgentHomeRepository;
import com.susukkang.fgc.agenthome.repository.AgentHomeRepository.Candidate;
import com.susukkang.fgc.agenthome.repository.AgentHomeRepository.PortfolioRow;
import com.susukkang.fgc.agenthome.repository.AgentHomeRepository.StoredCheck;
import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.AgentDataScopeService;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.report.repository.AgentStatementRepository;
import com.susukkang.fgc.report.dto.StatementSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * FUN-086 설계사 홈(IF-API-74)과 계약 확인 기록(IF-API-74A).
 *
 * <p>이번 달 수수료의 보류·환수는 FUN-060 명세 저장소의 합계를 그대로 쓴다(같은 본인 귀속 기준).
 * 예정은 PLANNED 스케줄 행이라 보류(HOLD)와 겹치지 않고, 실제 지급액과 섞지 않는다.
 */
@Service
public class AgentHomeService {

    static final int PRIORITY_LIMIT = 20;
    static final String UNKNOWN = "UNKNOWN";
    /** 사람 확인이 필요한 상태 사건. 프로젝트 가정이며 정상화(ACTIVE)·청약 단계는 후보로 만들지 않는다. */
    public static final List<String> REVIEW_EVENT_STATUSES = List.of("UNPAID", "LAPSED", "REVIVED", "CANCELLED", "TERMINATED");
    // 전체 기간에도 빈 달까지 반환하므로 한 요청의 월별 버킷 수를 제한한다.
    private static final int MAX_PORTFOLIO_MONTHS = 100 * 12;
    private static final Set<String> PERIODS = Set.of("6", "12", "ALL");
    private static final Set<String> INSURER_TYPES = Set.of("ALL", "LIFE", "NON_LIFE");
    private static final Map<String, String> CATEGORY_LABEL = Map.of(
            "HEALTH_PROTECTION", "건강보장", "TERM_PROTECTION", "정기보장",
            "LONG_TERM_NONLIFE", "장기손해", "SAVINGS", "연금 및 저축");

    private final AgentHomeRepository repository;
    private final AgentStatementRepository statementRepository;
    private final AgentDataScopeService scope;
    private final int recentEventDays;

    public AgentHomeService(AgentHomeRepository repository, AgentStatementRepository statementRepository,
                            AgentDataScopeService scope,
                            @Value("${fgc.agent-home.recent-event-days:30}") int recentEventDays) {
        this.repository = repository;
        this.statementRepository = statementRepository;
        this.scope = scope;
        this.recentEventDays = recentEventDays;
    }

    @Transactional(readOnly = true)
    public AgentHomeResponse home(LocalDate asOfParam, String periodParam, String insurerTypeParam, Long insurerId) {
        long agentId = agentId();
        LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
        LocalDate asOf = asOfParam == null ? today : asOfParam;
        if (asOf.getYear() < 1 || asOf.getYear() > 9999) {
            throw invalid("asOf");
        }
        String period = periodParam == null ? "12" : periodParam;
        String insurerType = insurerTypeParam == null ? "ALL" : insurerTypeParam;
        if (!PERIODS.contains(period)) {
            throw invalid("period");
        }
        if (!INSURER_TYPES.contains(insurerType)) {
            throw invalid("insurerType");
        }
        if (insurerId != null && insurerId < 1) {
            throw invalid("insurerId");
        }

        Range range = range(agentId, asOf, period);
        long months = ChronoUnit.MONTHS.between(YearMonth.from(range.from()), YearMonth.from(range.to())) + 1;
        if (months > MAX_PORTFOLIO_MONTHS) {
            throw invalid("asOf");
        }
        OffsetDateTime asOfEnd = asOf.plusDays(1).atStartOfDay(DateUtil.SEOUL_ZONE).toOffsetDateTime();
        List<String> warnings = new ArrayList<>();

        List<PortfolioRow> rows = repository.portfolio(agentId, range.from(), range.to(), asOfEnd,
                "ALL".equals(insurerType) ? null : insurerType, insurerId);
        Portfolio portfolio = portfolio(rows, range, asOf, today, warnings);
        MyCommission commission = commission(agentId, asOf);

        OffsetDateTime recentFrom = asOf.minusDays(recentEventDays).atStartOfDay(DateUtil.SEOUL_ZONE).toOffsetDateTime();
        List<Candidate> candidates = repository.candidates(agentId, asOf, asOfEnd, recentFrom,
                REVIEW_EVENT_STATUSES, PRIORITY_LIMIT);
        List<PriorityContract> priority = candidates.stream().map(c -> priority(c, asOf)).toList();
        int total = candidates.isEmpty() ? 0 : (int) candidates.get(0).total();

        String status = warnings.isEmpty() ? AgentHomeResponse.AVAILABLE : AgentHomeResponse.PARTIAL;
        return new AgentHomeResponse(asOf, period, range, new Filters(insurerType, insurerId), priority, total,
                commission, portfolio, new Basis(asOf, range, warnings), status);
    }

    /** 6/12 는 asOf 월을 포함한 N개월의 첫날~asOf, ALL 은 본인 최초 계약일~asOf. */
    private Range range(long agentId, LocalDate asOf, String period) {
        if ("ALL".equals(period)) {
            LocalDate first = repository.firstContractDate(agentId).orElse(asOf);
            return new Range(first.isAfter(asOf) ? asOf : first, asOf);
        }
        return new Range(asOf.withDayOfMonth(1).minusMonths(Integer.parseInt(period) - 1L), asOf);
    }

    private Portfolio portfolio(List<PortfolioRow> rows, Range range, LocalDate asOf, LocalDate today,
                                List<String> warnings) {
        Map<Long, long[]> insurerCount = new LinkedHashMap<>();
        Map<Long, String> insurerName = new LinkedHashMap<>();
        Map<Long, BigDecimal> insurerPremium = new LinkedHashMap<>();
        Map<String, long[]> monthCount = new TreeMap<>();
        Map<String, BigDecimal> monthPremium = new TreeMap<>();
        for (LocalDate m = range.from().withDayOfMonth(1); !m.isAfter(range.to()); m = m.plusMonths(1)) {
            monthCount.put(m.toString().substring(0, 7), new long[1]);
            monthPremium.put(m.toString().substring(0, 7), BigDecimal.ZERO);
        }
        Map<String, long[]> categories = new LinkedHashMap<>();
        Map<String, long[]> statuses = new LinkedHashMap<>();
        long unknownStatus = 0;
        for (PortfolioRow r : rows) {
            insurerCount.computeIfAbsent(r.insurerId(), k -> new long[1])[0]++;
            insurerName.put(r.insurerId(), r.insurerName());
            insurerPremium.merge(r.insurerId(), r.monthlyEquivalentFirstPremium(), BigDecimal::add);
            String month = r.contractDate().toString().substring(0, 7);
            monthCount.get(month)[0]++;
            monthPremium.merge(month, r.monthlyEquivalentFirstPremium(), BigDecimal::add);
            categories.computeIfAbsent(r.productGroupCode() == null ? UNKNOWN : r.productGroupCode(), k -> new long[1])[0]++;
            // 오늘 이후 기준일은 현재 상태가 곧 그 시점 상태다. 과거 기준일에 이력이 없으면 현재값으로 단정하지 않는다.
            String status = asOf.isBefore(today) ? r.statusAsOf() : r.currentStatus();
            if (status == null) {
                unknownStatus++;
                status = UNKNOWN;
            }
            statuses.computeIfAbsent(status, k -> new long[1])[0]++;
        }
        if (unknownStatus > 0) {
            warnings.add("기준일 시점의 상태 이력이 없는 계약 " + unknownStatus + "건은 현재 상태로 단정하지 않고 '상태 미확인'으로 집계했다.");
        }
        List<InsurerCount> insurers = insurerCount.entrySet().stream()
                .map(e -> new InsurerCount(e.getKey(), insurerName.get(e.getKey()), e.getValue()[0],
                        insurerPremium.get(e.getKey()))).toList();
        List<MonthlyCount> monthly = monthCount.entrySet().stream()
                .map(e -> new MonthlyCount(e.getKey(), e.getValue()[0], monthPremium.get(e.getKey()))).toList();
        List<CodeCount> categoryList = categories.entrySet().stream()
                .map(e -> new CodeCount(e.getKey(), categoryLabel(e.getKey()), e.getValue()[0])).toList();
        List<CodeCount> statusList = statuses.entrySet().stream()
                .map(e -> new CodeCount(e.getKey(), statusLabel(e.getKey()), e.getValue()[0])).toList();
        return new Portfolio(rows.size(), insurers, monthly, categoryList, statusList);
    }

    private MyCommission commission(long agentId, LocalDate asOf) {
        LocalDate month = asOf.withDayOfMonth(1);
        AgentHomeRepository.ExpectedSum expected = repository.expected(agentId, month);
        StatementSummary summary = statementRepository.summary(
                new AgentStatementRepository.Filter(agentId, month, null, null, null));
        return new MyCommission(month.toString().substring(0, 7),
                new Bucket(expected.amount(), expected.count()),
                new Bucket(summary.held().amount(), summary.held().count()),
                new Bucket(summary.clawback().amount(), summary.clawback().count()),
                AgentHomeResponse.AVAILABLE);
    }

    private PriorityContract priority(Candidate c, LocalDate asOf) {
        List<String> reasons = new ArrayList<>();
        if (c.followUp()) {
            reasons.add("FOLLOW_UP");
        }
        if (c.statusEvent()) {
            reasons.add("STATUS_EVENT_UNCONFIRMED");
        }
        if (c.heldLine()) {
            reasons.add("HOLD_UNRESOLVED");
        }
        Long elapsed = c.lastEventEffectiveAt() == null ? null : ChronoUnit.DAYS.between(
                c.lastEventEffectiveAt().atZoneSameInstant(DateUtil.SEOUL_ZONE).toLocalDate(), asOf);
        LastCheck last = c.lastCheckResult() == null ? null
                : new LastCheck(c.lastCheckResult(), c.lastCheckedAt(), c.lastNextCheckDate());
        return new PriorityContract(c.contractId(), c.contractNo(), c.insurerName(), c.productName(),
                statusLabel(c.currentStatus()), c.currentStatus(), reasons, c.followUp() ? c.nextCheckDate() : null,
                c.lastEventEffectiveAt(), elapsed, contextVersion(c.contractId(), c.maxEventSeq()), last,
                c.expectedAmount(), c.heldAmount(), c.heldCount());
    }

    /** IF-API-74A. 같은 멱등키·같은 본문은 처음 기록을 그대로 돌려주고, 다른 본문이면 409 다. */
    @Transactional
    public ContractCheckResponse recordCheck(ContractCheckRequest request, String idempotencyKey) {
        long agentId = agentId();
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160) {
            throw invalid("Idempotency-Key");
        }
        boolean followUp = "FOLLOW_UP".equals(request.result());
        if (followUp != (request.nextCheckDate() != null)) {
            throw invalid("nextCheckDate");
        }
        // FUN-085: 키 재사용·잠금 결과보다 먼저 본인 범위를 검사한다. 담당 변경 뒤의 재생도 다시 검사한다.
        scope.requireContract(request.contractId());
        String hash = hash(request);
        if (!repository.tryLock(agentId, idempotencyKey)) {
            throw new FgcBusinessException(FgcErrorCode.IMPL_REQUEST_IN_PROGRESS, null, Map.of(), null);
        }
        var existing = repository.findByIdempotencyKey(agentId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, request.contractId());
        }
        int seenSeq = repository.maxEventSeq(request.contractId());
        String current = contextVersion(request.contractId(), seenSeq);
        if (!current.equals(request.expectedContextVersion())) {
            throw new FgcBusinessException(FgcErrorCode.IMPL_STATE_CHANGED, "expectedContextVersion",
                    Map.of("contextVersion", current), null);
        }
        StoredCheck saved = repository.insert(agentId, request.contractId(), request.result(), request.nextCheckDate(),
                request.memo(), idempotencyKey, hash, current, seenSeq);
        return new ContractCheckResponse(saved.checkId(), saved.checkedAt(), saved.contractId(), saved.contextVersion());
    }

    private ContractCheckResponse replay(StoredCheck stored, String hash, long contractId) {
        if (!stored.requestHash().equals(hash) || stored.contractId() != contractId) {
            throw new FgcBusinessException(FgcErrorCode.IMPL_IDEMPOTENCY_CONFLICT, null, Map.of(), null);
        }
        return new ContractCheckResponse(stored.checkId(), stored.checkedAt(), stored.contractId(), stored.contextVersion());
    }

    static String contextVersion(long contractId, int maxEventSeq) {
        return "contract" + contractId + "-event" + maxEventSeq;
    }

    private long agentId() {
        Long agentId = scope.currentAgentId();
        if (agentId == null) {
            throw new FgcBusinessException(FgcErrorCode.AUTH_003);
        }
        return agentId;
    }

    private static String categoryLabel(String code) {
        return UNKNOWN.equals(code) ? "분류 미확인" : CATEGORY_LABEL.getOrDefault(code, code);
    }

    private static String statusLabel(String code) {
        if (UNKNOWN.equals(code)) {
            return "상태 미확인";
        }
        try {
            return ContractStatus.valueOf(code).label();
        } catch (IllegalArgumentException ex) {
            return code;
        }
    }

    private static String hash(ContractCheckRequest r) {
        String canonical = String.join("|", String.valueOf(r.contractId()), r.result(),
                String.valueOf(r.nextCheckDate()), r.memo() == null ? "" : r.memo(), r.expectedContextVersion());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }
}
