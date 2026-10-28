-- 설계사 시연 계정 agent01 활성화. V64 는 비밀번호 미지정(!)·DISABLED 로 시드하므로
-- 시연 서버·로컬에서 설계사 화면에 로그인할 수 없다. 팀이 정한 시연용 비밀번호의 BCrypt 해시로 활성화한다.
-- 평문 비밀번호는 저장소에 두지 않는다. password_hash='!' 인 행만 바꾸므로 이미 설정된 계정은 덮어쓰지 않는다.
-- 주의: db/demo 는 신한 prd 도 읽는다. 외부 트래픽을 열기 전에 agent01 비밀번호 변경·잠금을 검증한다
--   (FGC_정규화_입력데이터_시드데이터_명세서 §5).
SET search_path TO fgc, public;

UPDATE app_user
   SET password_hash = '$2a$10$YJBT7TxnOPeJDNTjav1xOuzONVbCHmHXHDUsytIR1BQNELxuFum8m',
       account_status = 'ACTIVE'
 WHERE login_id = 'agent01'
   AND password_hash = '!';
