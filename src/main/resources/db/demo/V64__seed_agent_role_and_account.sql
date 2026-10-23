-- FUN-085 / #634. develop의 V59~V61 다음 시드를 V64로 분리한다.
-- 근거: FGC_정규화_입력데이터_시드데이터_명세서_v1_0.md §5 GOLDEN 사용자.
-- 새 설계사·계약을 만들지 않고 기존 A-FC-001에 대표 계정 1개를 연결한다.
-- 공유 비밀번호를 복제하지 않는다. 별도 BCrypt 비밀번호 설정 후 ACTIVE로 활성화한다.
-- '!'는 로그인할 수 없는 비밀번호 미지정 표식이며 초기 계정 상태도 DISABLED다.
SET search_path TO fgc, public;

DO $$
DECLARE
  v_role_code CONSTANT text := 'AGENT';
  v_agent_code CONSTANT text := 'A-FC-001';
  v_login_id CONSTANT text := 'agent01';
BEGIN
  INSERT INTO app_role(role_code, role_name, description)
  VALUES (v_role_code, '설계사', 'FUN-085: 본인 담당 계약·수령 지급·작성 문서 범위')
  ON CONFLICT (role_code) DO NOTHING;

  IF NOT EXISTS (SELECT 1 FROM agent WHERE agent_code = v_agent_code) THEN
    RAISE EXCEPTION 'FUN-085 seed requires GOLDEN agent %', v_agent_code;
  END IF;

  INSERT INTO app_user(login_id, password_hash, user_name, role_id, agent_id, organization_id, account_status)
  SELECT v_login_id, '!', '설계사 시연 계정', r.role_id, a.agent_id, a.organization_id, 'DISABLED'
  FROM app_role r CROSS JOIN agent a
  WHERE r.role_code = v_role_code AND a.agent_code = v_agent_code
  ON CONFLICT (login_id) DO NOTHING;

  -- 다른 연결/역할을 이미 가진 계정을 덮어쓰지 않고 충돌을 알려준다.
  IF NOT EXISTS (
    SELECT 1 FROM app_user u JOIN app_role r USING (role_id)
    JOIN agent a ON a.agent_id = u.agent_id
    WHERE u.login_id = v_login_id AND r.role_code = v_role_code AND a.agent_code = v_agent_code
  ) THEN
    RAISE EXCEPTION 'FUN-085 seed conflicts with existing % mapping', v_login_id;
  END IF;
END $$;
