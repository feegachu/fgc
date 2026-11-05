package com.susukkang.fgc.dashboard.service;

import com.susukkang.fgc.agenthome.service.AgentHomeService;
import com.susukkang.fgc.base.dto.AgentResponse;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.dashboard.dto.HomeSummaryResponse;
import com.susukkang.fgc.dashboard.dto.HomeSummaryResponse.HomeAgentCounts;
import com.susukkang.fgc.dashboard.dto.HomeSummaryResponse.HomeAttentionAgent;
import com.susukkang.fgc.dashboard.dto.HomeSummaryResponse.HomeAttentionReason;
import com.susukkang.fgc.dashboard.dto.HomeSummaryResponse.HomeBranch;
import com.susukkang.fgc.dashboard.repository.HomeSummaryQueryRepository;
import com.susukkang.fgc.dashboard.repository.HomeSummaryQueryRepository.AgentRow;
import com.susukkang.fgc.dashboard.repository.HomeSummaryQueryRepository.BranchRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * IF-API-68(FUN-080) 관리자 홈 GA·설계사 현황.
 *
 * <p>등록은 IF-API-07 기본 조회와 같은 위촉 기간 조건이고, 활동은 그중 상태가 활동이며 기준정보가 사용 중인 설계사다.
 * 상태 이력(agent_history)은 비어 있어 활동 여부는 현재 상태값을 쓴다.
 * 담당 변경·승계는 쓰지 않는다. 계약은 insurance_contract.agent_id 의 모집 설계사에게만 붙는다.
 */
@Service
public class HomeSummaryService {

    private static final int ATTENTION_LIMIT = 10;
    /** 유지 계약(운영정책서 2단계 대상 선별 기준 #474, SRC-023 별표15 제26조). 미납은 납입최고기간이 끝나기 전까지 유지다. */
    private static final List<String> VALID_CONTRACT_STATUSES = List.of("ACTIVE", "UNPAID", "REVIVED");

    /** 업무 규칙이 아니라 가상 GA 정책이다. 미처리 예외, 한도 근접, 최근 상태 변화 순으로 앞에 두고 코드로 동률을 푼다. */
    private static final Comparator<AgentRow> ATTENTION_ORDER = Comparator
            .comparingLong(AgentRow::openExceptionCount).reversed()
            .thenComparing(Comparator.comparingLong(AgentRow::nearLimitCount).reversed())
            .thenComparing(Comparator.comparingLong(AgentRow::recentChangeCount).reversed())
            .thenComparing(AgentRow::code);

    private final HomeSummaryQueryRepository repository;
    private final int recentEventDays;

    /** 최근 상태 변화 기간과 대상 사건은 설계사 홈(FUN-086)과 같은 값을 쓴다. */
    public HomeSummaryService(HomeSummaryQueryRepository repository,
                              @Value("${fgc.agent-home.recent-event-days:30}") int recentEventDays) {
        this.repository = repository;
        this.recentEventDays = recentEventDays;
    }

    @Transactional(readOnly = true)
    public HomeSummaryResponse summarize(LocalDate asOfParam) {
        LocalDate today = LocalDate.now(DateUtil.SEOUL_ZONE);
        LocalDate asOf = asOfParam == null ? today : asOfParam;
        OffsetDateTime asOfEnd = asOf.plusDays(1).atStartOfDay(DateUtil.SEOUL_ZONE).toOffsetDateTime();
        OffsetDateTime recentFrom = asOf.minusDays(recentEventDays).atStartOfDay(DateUtil.SEOUL_ZONE).toOffsetDateTime();

        List<AgentRow> agents = repository.agentRows(asOf, asOfEnd, recentFrom,
                ExceptionStatus.dbStatuses(ExceptionStatus.OPEN_FILTER).stream().map(Enum::name).toList(),
                AgentHomeService.REVIEW_EVENT_STATUSES);
        List<BranchRow> branches = repository.branchRows(asOf, asOfEnd, asOf.isBefore(today), VALID_CONTRACT_STATUSES);

        List<AgentRow> registered = agents.stream().filter(agent -> isRegistered(agent, asOf)).toList();
        List<AgentRow> attention = agents.stream().filter(HomeSummaryService::needsAttention).sorted(ATTENTION_ORDER).toList();
        return new HomeSummaryResponse(
                asOf,
                new HomeAgentCounts(registered.size(),
                        registered.stream().filter(agent -> "ACTIVE".equals(agent.status()) && agent.activeYn()).count(),
                        (long) agents.size() - registered.size()),
                branchRows(branches, registered, attention),
                attention.size(),
                attention.stream().limit(ATTENTION_LIMIT).map(HomeSummaryService::attentionAgent).toList(),
                ("등록은 기준일에 위촉 중인 설계사, 활동은 그중 상태가 활동이고 기준정보가 사용 중인 설계사, 해촉은 기준일 전에 위촉이 끝난 설계사입니다. "
                        + "유효 계약은 기준일 상태가 정상, 미납, 부활인 계약입니다. "
                        + "확인 사유는 지금 미처리인 예외, 최신 판정이 주의인 계약(계약별로 세며 금액을 합치지 않습니다), 최근 %d일 안의 상태 변화입니다. "
                        + "지점별 설계사 수는 등록 설계사만 세고, 확인할 설계사 수에는 사유가 있는 해촉 설계사도 들어갑니다. "
                        + "지점 밖 조직에 바로 소속된 설계사는 그 조직 행에 보입니다.")
                        .formatted(recentEventDays));
    }

    private static boolean isRegistered(AgentRow agent, LocalDate asOf) {
        return agent.terminationDate() == null || !agent.terminationDate().isBefore(asOf);
    }

    private static boolean needsAttention(AgentRow agent) {
        return agent.openExceptionCount() + agent.nearLimitCount() + agent.recentChangeCount() > 0;
    }

    /** 소계 합이 전체와 맞도록 지점 밖 조직에 있는 설계사도 그 조직 행으로 남긴다. 정렬은 조직 코드다. */
    private static List<HomeBranch> branchRows(List<BranchRow> branches, List<AgentRow> registered, List<AgentRow> attention) {
        Map<Long, Long> agentCount = countByBranch(registered);
        Map<Long, Long> attentionCount = countByBranch(attention);
        Map<Long, BranchRow> rows = Stream.concat(branches.stream(),
                        Stream.concat(registered.stream(), attention.stream())
                                .map(agent -> new BranchRow(agent.branchId(), agent.branchCode(), agent.branchName(), 0)))
                .collect(Collectors.toMap(BranchRow::organizationId, Function.identity(), (first, second) -> first, LinkedHashMap::new));
        return rows.values().stream()
                .sorted(Comparator.comparing(BranchRow::code))
                .map(row -> new HomeBranch(row.organizationId(), row.name(),
                        agentCount.getOrDefault(row.organizationId(), 0L), row.activeContractCount(),
                        attentionCount.getOrDefault(row.organizationId(), 0L)))
                .toList();
    }

    private static Map<Long, Long> countByBranch(List<AgentRow> agents) {
        return agents.stream().collect(Collectors.groupingBy(AgentRow::branchId, Collectors.counting()));
    }

    private static HomeAttentionAgent attentionAgent(AgentRow agent) {
        List<HomeAttentionReason> reasons = new ArrayList<>();
        addReason(reasons, "OPEN_EXCEPTION", "미처리 예외", agent.openExceptionCount());
        addReason(reasons, "NEAR_LIMIT", "한도 근접", agent.nearLimitCount());
        addReason(reasons, "RECENT_STATUS_CHANGE", "최근 상태 변화", agent.recentChangeCount());
        return new HomeAttentionAgent(agent.agentId(), agent.name(), agent.code(), agent.branchName(), agent.status(),
                AgentResponse.statusLabel(agent.status()), agent.activeYn(), agent.contractCount(), reasons);
    }

    private static void addReason(List<HomeAttentionReason> reasons, String code, String label, long count) {
        if (count > 0) {
            reasons.add(new HomeAttentionReason(code, label, count));
        }
    }
}
