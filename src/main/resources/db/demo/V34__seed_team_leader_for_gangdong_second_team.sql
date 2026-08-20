-- 2026-08-19 hjKang - 강동2팀(FGC-T010102) 팀장 시드 보강
-- 기존 코드: V3 시드가 팀장을 강동1팀(FGC-T010101)의 A-TL-001 한 명만 넣었다.
-- 문제: 강동2팀 소속 A-FC-003(박신인)의 계약 FGC-FGL01-202607-0003(C006)은
--       GA_TO_FC 수수료규칙에 TEAM_LEADER 행(40%)이 있는데,
--       AgentMapper.findActiveAgentIdFromOrganizationHierarchy 의 재귀 CTE 가
--       조상 조직만 거슬러 올라가고 형제 팀은 보지 않으므로 팀장을 찾지 못한다.
--       ScheduleService.resolveBeneficiaryAgentId 가 FgcBusinessException 을 던지고
--       ScheduleRegenerationBatchItemService 의 REQUIRES_NEW 트랜잭션이 통째로 롤백되어
--       먼저 만들어진 INSURER_TO_GA 스케줄까지 사라진다.
--       그 결과 C006 은 예상 스케줄이 0건이라 대사에서 실제 원천만 남아
--       전부 EXPECTED_MISSING 예외가 된다.
-- 개선: 운영정책서 제1조의 조직구조(본사1 → 본부4 → 지사20 → 팀100)대로 팀마다 팀장이 있어야 하므로
--       강동2팀 팀장을 보강한다. 조직 계층 조회 로직은 바꾸지 않는다 —
--       형제 팀의 팀장이 관리자수수료를 받는 것은 업무적으로 틀린 결과이기 때문이다.
--
-- 위촉일은 강동2팀 계약 중 가장 이른 계약일(2026-07-22)보다 충분히 앞선 날짜를 쓴다.
-- findActiveAgentIdFromOrganizationHierarchy 가 계약일 기준으로 appointment_date 를 거른다.

INSERT INTO agent(organization_id, agent_code, agent_name, rank_code,
                  appointment_date, termination_date, agent_status,
                  latest_registration_date, prior_three_year_experience_yn, experience_checked_on,
                  newcomer_support_eligible_yn, newcomer_support_end_date)
SELECT o.organization_id, v.code, v.name, v.rank,
       v.appoint, v.term, v.status,
       v.reg_date, v.prior3y, v.checked_on, v.newcomer_yn, v.newcomer_end
  FROM (VALUES
    ('A-TL-002','오팀장','TEAM_LEADER',     'FGC-T010102', DATE '2021-06-01', NULL::date,        'ACTIVE',
     DATE '2021-06-01', true,  DATE '2021-05-31', false, NULL::date)
  ) AS v(code,name,rank,org,appoint,term,status,reg_date,prior3y,checked_on,newcomer_yn,newcomer_end)
  JOIN organization o ON o.organization_code = v.org
ON CONFLICT (agent_code) DO NOTHING;

-- 확인용 (psql)
-- WITH RECURSIVE h AS (
--   SELECT organization_id, parent_id, 0 AS d FROM fgc.organization
--    WHERE organization_code = 'FGC-T010102'
--   UNION ALL
--   SELECT p.organization_id, p.parent_id, h.d + 1
--     FROM fgc.organization p JOIN h ON p.organization_id = h.parent_id)
-- SELECT h.d, o.organization_code, a.agent_code, a.rank_code
--   FROM h JOIN fgc.organization o ON o.organization_id = h.organization_id
--   LEFT JOIN fgc.agent a ON a.organization_id = h.organization_id
--  ORDER BY h.d;
--   → d=0 강동2팀에 TEAM_LEADER 가 보이면 정상
