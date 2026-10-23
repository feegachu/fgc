package com.susukkang.fgc.dataset;

import com.susukkang.fgc.dataset.Dar012Plan.AgentCodeRow;
import com.susukkang.fgc.dataset.Dar012Plan.AgentRow;
import com.susukkang.fgc.dataset.Dar012Plan.AnswerRow;
import com.susukkang.fgc.dataset.Dar012Plan.BatchRow;
import com.susukkang.fgc.dataset.Dar012Plan.ContractRow;
import com.susukkang.fgc.dataset.Dar012Plan.EventRow;
import com.susukkang.fgc.dataset.Dar012Plan.OrgRow;
import com.susukkang.fgc.dataset.Dar012Plan.PaymentRow;
import com.susukkang.fgc.dataset.Dar012Plan.SnapshotRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * DAR-012 기초 데이터 계획기 (시드명세 §50). DB·시계에 의존하지 않는 순수 함수라서
 * 같은 seed·계약 수·기준일이면 항상 같은 계획을 만든다.
 *
 * <p>정책값(수수료율·1,200% 배수)은 코드에 박지 않고 {@link PolicyInputs}로 받는다(AGENTS.md §4).
 * 금액은 지급행 단위로 원 단위 HALF_UP 반올림한 뒤 합산한다.
 */
public final class Dar012Planner {

    public static final String GENERATOR_VERSION = "1.0.0";
    public static final List<String> INSURER_CODES = List.of("FGL01", "FGL02", "FGL03", "FGL04", "FGN01");
    public static final int BRANCH_COUNT = 12;
    public static final int TEAMS_PER_BRANCH = 2;
    public static final int AGENT_COUNT = 600;
    public static final int ACTIVE_AGENT_COUNT = 540;
    public static final int INACTIVE_AGENT_COUNT = 20;
    public static final int FIRST_CONTRACT_SEQ = 5001;
    public static final int REPRESENTATIVE_CONTRACTS = 5;

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final String[] SURNAMES = {"김", "이", "박", "최", "정", "강", "조", "윤", "장", "임"};
    private static final String[] GIVEN = {"민준", "서연", "지후", "하윤", "도윤", "서준", "지민", "예준", "수아", "채원", "시우", "지유"};
    private static final int[] TERMS = {120, 180, 240, 360};
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** DB의 승인된 정책에서 읽은 값. */
    public record PolicyInputs(BigDecimal insurerBaseRatePct, BigDecimal capPremiumMultiplier) {}

    private Dar012Planner() {}

    public static Dar012Plan plan(String datasetId, long seed, int contractCount, LocalDate baseDate, PolicyInputs policy) {
        SplittableRandom root = new SplittableRandom(seed);
        SplittableRandom orgRng = root.split();
        SplittableRandom agentRng = root.split();
        SplittableRandom contractRng = root.split();

        List<OrgRow> orgs = organizations();
        List<AgentRow> agents = agents(agentRng, baseDate);
        List<AgentCodeRow> codes = agentCodes(agents);
        // 조직은 고정 구조라 난수가 필요 없다. 분리해 둔 split은 이후 확장 시 다른 난수열이 밀리지 않게 하기 위함이다.
        orgRng.nextInt();

        LocalDate windowStart = baseDate.withDayOfMonth(1).minusMonths(2);
        LocalDate windowEnd = baseDate.withDayOfMonth(1).minusDays(1);
        int scenarioDayLimit = (int) Math.max(0, Math.min(40, ChronoUnit.DAYS.between(windowStart, baseDate) - 31));

        List<AgentRow> activeAgents = agents.stream().filter(a -> "ACTIVE".equals(a.status())).toList();

        List<ContractRow> contracts = new ArrayList<>();
        List<EventRow> events = new ArrayList<>();
        List<SnapshotRow> snapshots = new ArrayList<>();
        List<AnswerRow> answers = new ArrayList<>();
        Map<String, Integer> seqCounter = new HashMap<>();
        List<ContractDraft> drafts = new ArrayList<>();

        for (int i = 0; i < contractCount; i++) {
            boolean representative = i < REPRESENTATIVE_CONTRACTS;
            String statusPath = representative ? repPath(i) : statusPath(contractRng.nextInt(1000));
            String insurer = representative ? (i % 2 == 0 ? "FGL01" : "FGL02")
                    : INSURER_CODES.get(contractRng.nextInt(INSURER_CODES.size()));
            AgentRow agent = representative ? agents.get(0) : activeAgents.get(contractRng.nextInt(activeAgents.size()));
            int dayLimit = "NORMAL".equals(statusPath) ? (int) ChronoUnit.DAYS.between(windowStart, windowEnd) : scenarioDayLimit;
            LocalDate contractDate = representative ? windowStart.plusDays(4 + i)
                    : windowStart.plusDays(contractRng.nextInt(dayLimit + 1));
            BigDecimal monthly = BigDecimal.valueOf((30L + contractRng.nextInt(270)) * 1000L).setScale(2);
            int cycleRoll = contractRng.nextInt(100);
            int termMonths = TERMS[contractRng.nextInt(TERMS.length)];
            int deltaSign = contractRng.nextBoolean() ? 1 : -1;
            int deltaSteps = 1 + contractRng.nextInt(9);

            String yyyymm = "%04d%02d".formatted(contractDate.getYear(), contractDate.getMonthValue());
            int seq = FIRST_CONTRACT_SEQ + seqCounter.merge(insurer + yyyymm, 1, Integer::sum) - 1;
            if (seq > 9999) {
                throw new IllegalArgumentException("계약 일련번호가 4자리를 넘는다: 계약 수를 줄이거나 기간을 늘려야 한다");
            }
            String contractNo = "FGC-%s-%s-%04d".formatted(insurer, yyyymm, seq);

            int cycleMultiplier = cycleRoll < 80 ? 1 : cycleRoll < 90 ? 3 : cycleRoll < 95 ? 6 : 12;
            String cycleCode = switch (cycleMultiplier) {
                case 1 -> "MONTHLY";
                case 3 -> "QUARTERLY";
                case 6 -> "SEMI_ANNUAL";
                default -> "ANNUAL";
            };
            String ruleCode = switch (cycleMultiplier) {
                case 1 -> "MONTHLY_AS_IS";
                case 3 -> "MONTHLY_TO_QUARTERLY_X3";
                case 6 -> "MONTHLY_TO_SEMI_ANNUAL_X6";
                default -> "MONTHLY_TO_ANNUAL_X12";
            };
            BigDecimal perCycle = monthly.multiply(BigDecimal.valueOf(cycleMultiplier)).setScale(2);

            List<EventRow> contractEvents = events(contractNo, contractDate, statusPath);
            String currentStatus = contractEvents.get(contractEvents.size() - 1).newStatus();
            contracts.add(new ContractRow(contractNo, insurer, agent.code(), agent.orgCode(), contractDate, perCycle,
                    monthly, cycleCode, ruleCode, termMonths, currentStatus));
            events.addAll(contractEvents);
            snapshots.add(new SnapshotRow(contractNo, contractDate, perCycle));
            drafts.add(new ContractDraft(i, contractNo, insurer, agent.code(), contractDate, monthly, statusPath,
                    deltaSign * deltaSteps * 1000L));
            statusAnswer(statusPath, contractNo, answers);
        }

        List<BatchRow> batches = new ArrayList<>();
        List<PaymentRow> payments = new ArrayList<>();
        Map<String, Integer> rowCounter = new LinkedHashMap<>();
        for (ContractDraft d : drafts) {
            LocalDate month = d.contractDate().withDayOfMonth(1);
            String batchKey = d.insurer() + "|" + month;
            if (!rowCounter.containsKey(batchKey)) {
                rowCounter.put(batchKey, 0);
                String yyyymm = "%04d%02d".formatted(month.getYear(), month.getMonthValue());
                batches.add(new BatchRow(d.insurer(), month, "SYN-%s-%s-COMM".formatted(d.insurer(), yyyymm),
                        month.plusMonths(1).withDayOfMonth(5),
                        "SYNTHETIC:DAR012:%s:%s:%s:COMMISSION".formatted(datasetId, d.insurer(), yyyymm)));
            }
            if ("REP_UNCONFIRMED".equals(d.statusPath())) {
                BigDecimal expected = expectedAmount(d.monthly(), policy);
                answers.add(new AnswerRow(d.contractNo(), "REP_AMOUNT_UNCONFIRMED", "RECON_MISSING_STATEMENT", expected,
                        "대표 설계사 금액 미확인 사례 — 원수사 명세에 지급행이 없다"));
                continue;
            }
            BigDecimal expected = expectedAmount(d.monthly(), policy);
            BigDecimal actual = expected;
            int i = d.index();
            boolean plainNormal = d.index() >= REPRESENTATIVE_CONTRACTS && "NORMAL".equals(d.statusPath());
            boolean commonPattern = plainNormal && "FGL03".equals(d.insurer())
                    && d.contractDate().isBefore(d.contractDate().withDayOfMonth(1).plusDays(7));
            if (commonPattern) {
                actual = expected.subtract(expected.multiply(BigDecimal.valueOf(10)).divide(HUNDRED, 0, RoundingMode.HALF_UP)
                        .setScale(2));
                answers.add(new AnswerRow(d.contractNo(), "COMMON_PATTERN_SHORT_10PCT", "RECON_AMOUNT_MISMATCH", expected,
                        "FGL03 매월 1~7일 계약은 원수사가 10%% 적게 지급 — 명세 %s".formatted(actual.toPlainString())));
            } else if (plainNormal && i % 100 == 7) {
                actual = expected.add(BigDecimal.valueOf(d.delta())).setScale(2);
                answers.add(new AnswerRow(d.contractNo(), "RECON_AMOUNT_DIFF", "RECON_AMOUNT_MISMATCH", expected,
                        "명세 금액 %s (차이 %d원)".formatted(actual.toPlainString(), d.delta())));
            } else if (plainNormal && i % 50 == 13) {
                actual = d.monthly().multiply(policy.capPremiumMultiplier()).setScale(0, RoundingMode.HALF_UP).setScale(2);
                answers.add(new AnswerRow(d.contractNo(), "CAP_AT_LIMIT", "CAP_AT_LIMIT", actual,
                        "단일 지급이 한도(월환산 보험료 x 한도 배수)와 정확히 같다"));
            } else if (plainNormal && i % 50 == 38) {
                BigDecimal limit = d.monthly().multiply(policy.capPremiumMultiplier()).setScale(0, RoundingMode.HALF_UP).setScale(2);
                actual = limit.add(BigDecimal.valueOf(1000)).setScale(2);
                answers.add(new AnswerRow(d.contractNo(), "CAP_OVER_LIMIT", "CAP_EXCEEDED", limit,
                        "단일 지급이 한도보다 1,000원 많다 — 명세 %s".formatted(actual.toPlainString())));
            }
            int rowNo = rowCounter.merge(batchKey, 1, Integer::sum);
            String yyyymm = "%04d%02d".formatted(month.getYear(), month.getMonthValue());
            LocalDate due = month.plusMonths(2).minusDays(1);
            payments.add(new PaymentRow("SYNTHETIC:DAR012:%s:%s:%s:%06d".formatted(datasetId, d.insurer(), yyyymm, rowNo),
                    d.insurer(), d.contractNo(), "D12-%s-%s".formatted(d.insurer(), d.agentCode().substring(6)), month, due,
                    due.isAfter(baseDate) ? null : due, actual, 1));
        }
        return new Dar012Plan(datasetId, seed, baseDate, orgs, agents, codes, contracts, events, snapshots, batches,
                payments, answers);
    }

    /** 지급행 단위 원 단위 HALF_UP 반올림. 율은 정책에서 읽은 값이다. */
    static BigDecimal expectedAmount(BigDecimal monthlyEquivalent, PolicyInputs policy) {
        return monthlyEquivalent.multiply(policy.insurerBaseRatePct()).divide(HUNDRED, 0, RoundingMode.HALF_UP).setScale(2);
    }

    private record ContractDraft(int index, String contractNo, String insurer, String agentCode, LocalDate contractDate,
                                 BigDecimal monthly, String statusPath, long delta) {}

    private static String repPath(int i) {
        return switch (i) {
            case 0 -> "NORMAL";
            case 1 -> "LAPSED";
            case 2 -> "REVIVED";
            case 3 -> "REP_UNCONFIRMED";
            default -> "TERMINATED";
        };
    }

    private static String statusPath(int roll) {
        if (roll < 900) return "NORMAL";
        if (roll < 940) return "UNPAID";
        if (roll < 960) return "LAPSED";
        if (roll < 970) return "REVIVED";
        if (roll < 990) return "TERMINATED";
        return "LATE_ARRIVAL";
    }

    private static void statusAnswer(String path, String contractNo, List<AnswerRow> answers) {
        String type = switch (path) {
            case "UNPAID" -> "STATUS_UNPAID";
            case "LAPSED" -> "STATUS_LAPSED";
            case "REVIVED" -> "STATUS_REVIVED";
            case "TERMINATED" -> "STATUS_TERMINATED";
            case "LATE_ARRIVAL" -> "STATUS_LATE_ARRIVAL";
            default -> null;
        };
        if (type != null) {
            String note = "TERMINATED".equals(path) ? "해지 사건 — 환수 대상 여부와 금액은 엔진이 계산한다" : "상태 사건 시나리오";
            answers.add(new AnswerRow(contractNo, "STATUS_" + path, type, null, note));
        }
    }

    private static List<EventRow> events(String contractNo, LocalDate date, String path) {
        List<EventRow> list = new ArrayList<>();
        list.add(event(contractNo, 1, null, "APPLIED", date, date, 0, "NEW_BUSINESS"));
        boolean late = "LATE_ARRIVAL".equals(path);
        list.add(event(contractNo, 2, "APPLIED", "ACTIVE", date.plusDays(1), date.plusDays(1), late ? 20 : 0, "ISSUED"));
        switch (path) {
            case "UNPAID" -> list.add(event(contractNo, 3, "ACTIVE", "UNPAID", date.plusDays(10), date.plusDays(10), 0, "PREMIUM_OVERDUE"));
            case "LAPSED" -> {
                list.add(event(contractNo, 3, "ACTIVE", "UNPAID", date.plusDays(10), date.plusDays(10), 0, "PREMIUM_OVERDUE"));
                list.add(event(contractNo, 4, "UNPAID", "LAPSED", date.plusDays(20), date.plusDays(20), 0, "LAPSE"));
            }
            case "REVIVED" -> {
                list.add(event(contractNo, 3, "ACTIVE", "UNPAID", date.plusDays(10), date.plusDays(10), 0, "PREMIUM_OVERDUE"));
                list.add(event(contractNo, 4, "UNPAID", "LAPSED", date.plusDays(20), date.plusDays(20), 0, "LAPSE"));
                list.add(event(contractNo, 5, "LAPSED", "REVIVED", date.plusDays(30), date.plusDays(30), 0, "REVIVAL"));
            }
            case "TERMINATED" -> list.add(event(contractNo, 3, "ACTIVE", "TERMINATED", date.plusDays(25), date.plusDays(25), 0, "CUSTOMER_TERMINATION"));
            default -> { }
        }
        return list;
    }

    private static EventRow event(String contractNo, int seq, String prev, String next, LocalDate effective,
                                  LocalDate received, int lateDays, String reason) {
        OffsetDateTime eff = effective.atTime(LocalTime.of(9, 0)).atOffset(KST);
        OffsetDateTime rec = received.plusDays(lateDays).atTime(LocalTime.of(10, 0)).atOffset(KST);
        return new EventRow(contractNo, seq, prev, next, eff, rec, reason);
    }

    private static List<OrgRow> organizations() {
        List<OrgRow> list = new ArrayList<>();
        for (int b = 1; b <= BRANCH_COUNT; b++) {
            String code = "D12-B%02d".formatted(b);
            list.add(new OrgRow(code, "가상지점 %02d".formatted(b), "BRANCH", b <= BRANCH_COUNT / 2 ? "FGC-D01" : "FGC-D02"));
            for (int t = 1; t <= TEAMS_PER_BRANCH; t++) {
                list.add(new OrgRow("%s-T%d".formatted(code, t), "가상지점 %02d %d팀".formatted(b, t), "TEAM", code));
            }
        }
        return list;
    }

    private static List<AgentRow> agents(SplittableRandom rng, LocalDate baseDate) {
        int teams = BRANCH_COUNT * TEAMS_PER_BRANCH;
        List<AgentRow> list = new ArrayList<>();
        for (int n = 1; n <= AGENT_COUNT; n++) {
            int team = (n - 1) % teams;
            String orgCode = "D12-B%02d-T%d".formatted(team / TEAMS_PER_BRANCH + 1, team % TEAMS_PER_BRANCH + 1);
            String name = SURNAMES[rng.nextInt(SURNAMES.length)] + GIVEN[rng.nextInt(GIVEN.length)];
            LocalDate appointment = LocalDate.of(2024, 1, 1).plusDays(rng.nextInt(880));
            String status;
            LocalDate termination = null;
            if (n <= ACTIVE_AGENT_COUNT) {
                status = "ACTIVE";
            } else if (n <= ACTIVE_AGENT_COUNT + INACTIVE_AGENT_COUNT) {
                status = "INACTIVE";
            } else {
                status = "TERMINATED";
                termination = LocalDate.of(2026, 3, 1).plusDays(rng.nextInt(120));
                if (termination.isBefore(appointment)) {
                    termination = appointment;
                }
            }
            list.add(new AgentRow("D12-A-%04d".formatted(n), name, orgCode, status, appointment, termination));
        }
        return list;
    }

    private static List<AgentCodeRow> agentCodes(List<AgentRow> agents) {
        List<AgentCodeRow> list = new ArrayList<>();
        for (AgentRow agent : agents) {
            String number = agent.code().substring(6);
            for (String insurer : INSURER_CODES) {
                list.add(new AgentCodeRow(insurer, agent.code(), "D12-%s-%s".formatted(insurer, number)));
            }
        }
        return list;
    }
}
