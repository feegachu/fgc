-- 설계사 시연 계정 agent01 활성화 (시연 서버·로컬 전용).
-- V64 는 agent01 을 비밀번호 미지정(!)·DISABLED 로 시드하므로 이대로는 설계사 화면에 로그인할 수 없다.
-- 이 파일은 db/demo 가 아닌 db/demo-agent 에 둔다 — db/demo 는 신한 prd 도 읽으므로, prd 에서는
-- 이 위치를 Flyway locations 에 넣지 않아 활성화되지 않게 한다(infra/shinhan/compose.yaml 은 그대로).
-- password_hash='!' 인 행만 바꾸므로 이미 비밀번호가 설정된 계정은 덮어쓰지 않는다.
SET search_path TO fgc, public;

UPDATE app_user
   SET password_hash = '$2a$10$YJBT7TxnOPeJDNTjav1xOuzONVbCHmHXHDUsytIR1BQNELxuFum8m',
       account_status = 'ACTIVE'
 WHERE login_id = 'agent01'
   AND password_hash = '!';
