-- 시연 서버·로컬 전용: 서부본부와 마포지사에 설계사 조직을 채운다.
-- 지점별 현황과 지점 간 비교를 보여 주려면 지점이 둘 이상 살아 있어야 한다. 기존 마포1팀에는 해촉자 한 명뿐이다.
-- 관리자수수료 회차는 본부장, 지사장, 팀장이 모두 있어야 생성되므로 직급 전체를 넣는다.
-- 금액과 사람은 가상이고 업무 규칙이 아니다.
SET search_path TO fgc, public;

INSERT INTO agent(organization_id, agent_code, agent_name, rank_code, appointment_date, agent_status,
                  latest_registration_date, prior_three_year_experience_yn, experience_checked_on,
                  newcomer_support_eligible_yn, active_yn)
SELECT o.organization_id, v.code, v.name, v.rank_code, v.appointed, 'ACTIVE', v.appointed, true, v.appointed, false, true
  FROM (VALUES
    ('FGC-D02',     'A-DH-002', '남본부', 'DIVISION_HEAD',  DATE '2019-03-04'),
    ('FGC-B0201',   'A-BM-002', '문지사', 'BRANCH_MANAGER', DATE '2020-05-11'),
    ('FGC-T020101', 'A-TL-003', '강팀장', 'TEAM_LEADER',    DATE '2021-09-01'),
    ('FGC-T020101', 'A-FC-005', '윤보험', 'FC',             DATE '2023-06-12'),
    ('FGC-T020101', 'A-FC-006', '장설계', 'FC',             DATE '2024-02-05')
  ) AS v(org_code, code, name, rank_code, appointed)
  JOIN organization o ON o.organization_code = v.org_code
ON CONFLICT (agent_code) DO NOTHING;

INSERT INTO agent_insurer_code(agent_id, insurer_id, insurer_agent_code, effective_from, code_status)
SELECT a.agent_id, i.insurer_id, i.insurer_code || '-' || a.agent_code, DATE '2026-01-01', 'ACTIVE'
  FROM agent a
  JOIN insurer i ON i.insurer_code IN ('FGL01', 'FGL02', 'FGL03', 'FGL04', 'FGN01')
 WHERE a.agent_code IN ('A-FC-005', 'A-FC-006')
   AND NOT EXISTS (SELECT 1 FROM agent_insurer_code c WHERE c.agent_id = a.agent_id AND c.insurer_id = i.insurer_id);
