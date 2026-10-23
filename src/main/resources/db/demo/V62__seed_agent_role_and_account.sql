-- FUN-085 / #634. develop의 V59~V61 다음 시드를 V62로 분리한다.
-- 근거: FGC_정규화_입력데이터_시드데이터_명세서_v1_0.md §5 GOLDEN 사용자.
-- 새 설계사·계약을 만들지 않고 기존 A-FC-001에 대표 계정 1개를 연결한다.
-- 비밀번호는 기존 GOLDEN 시연 자격 증명의 BCrypt 해시만 저장한다.
-- db/demo를 읽는 배포 환경에서는 기존 계정과 함께 agent01도 비밀번호 변경/잠금 검증 대상이다.
SET search_path TO fgc, public;

INSERT INTO app_role(role_code, role_name, description)
VALUES ('AGENT', '설계사', 'FUN-085: 본인 담당 계약·수령 지급·작성 문서 범위')
ON CONFLICT (role_code) DO NOTHING;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM agent WHERE agent_code = 'A-FC-001') THEN
    RAISE EXCEPTION 'FUN-085 seed requires GOLDEN agent A-FC-001';
  END IF;
END $$;

INSERT INTO app_user(login_id, password_hash, user_name, role_id, agent_id, organization_id)
SELECT 'agent01', '$2a$10$3VhRIpYzNmTU.gMF45BO5egd1RJWwb7lKDwbeR.jOzgTaO.Ks7iVi',
       '설계사 시연 계정', r.role_id, a.agent_id, a.organization_id
FROM app_role r CROSS JOIN agent a
WHERE r.role_code = 'AGENT' AND a.agent_code = 'A-FC-001'
ON CONFLICT (login_id) DO NOTHING;

-- 다른 연결/역할을 이미 가진 계정을 덮어쓰지 않고 충돌을 알려준다.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM app_user u JOIN app_role r USING (role_id)
    JOIN agent a ON a.agent_id = u.agent_id
    WHERE u.login_id = 'agent01' AND r.role_code = 'AGENT' AND a.agent_code = 'A-FC-001'
  ) THEN
    RAISE EXCEPTION 'FUN-085 seed conflicts with existing agent01 mapping';
  END IF;
END $$;
