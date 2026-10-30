-- 설계사 시연 계정 agent01 표시 이름을 '손설계'로 변경 (시연 서버·로컬 전용).
-- V64 는 이미 병합되어 수정할 수 없으므로 새 버전으로 바꾼다. 로그인 ID 와 설계사 연결은 그대로다.
SET search_path TO fgc, public;

UPDATE app_user
   SET user_name = '손설계'
 WHERE login_id = 'agent01'
   AND user_name <> '손설계';
