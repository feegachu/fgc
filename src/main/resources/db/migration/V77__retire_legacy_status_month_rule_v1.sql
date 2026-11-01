-- ============================================================================
-- V77 — 유산 STATUS-MONTH-RULE-2026 v1 폐기 (#648 FUN-015 · PR #806 리뷰)
--
-- 무엇이 문제였나
--   V2가 STATUS-MONTH-RULE-2026 v1을 작성자·승인자·파라미터 없이 ACTIVE로 넣었다(V2:182-203, V6 2-1).
--   데모 시드(db/demo V3 ①)는 이 행을 승계 기록 없이 RETIRED로 바꾸고 v2를 절차대로 냈지만,
--   db/migration만 적용한 DB에는 v1이 ACTIVE로 남는다.
--   FUN-015 정책 해석(ScheduleEligibilityPolicyService)은 파라미터 없는 정책을 통과시키지 않는다.
--   그런데 정책 API로 같은 코드의 새 판을 활성화하면 v1이 승계 구간(2026-01-01 ~ 새 적용일 전날)으로 남는다
--   (새 판 적용일은 '한국시간 내일 이후', 다른 코드의 정책은 구간 중복 FGC-POL-002). 그 구간 계약은 계속 판단할 수 없다.
--
-- 무엇을 하나
--   데모 V3 ①과 같은 처리만 한다: 승계 기록 없이 v1을 RETIRED로 바꾼다.
--   승계 기록이 없는 RETIRED는 계산용 적용 구간(V68 policy_application_version)에서 빠진다.
--   새 판은 이 파일에서 만들지 않는다. 운영 DB에는 작성자·승인자로 적을 실제 사용자가 없고,
--   없는 승인자를 지어내지 않는다(V6 2-1). 운영자가 POL-W01에서 v1을 [새 버전 작성]으로 복제해
--   같은 시작일(2026-01-01)의 새 판에 파라미터 3개를 넣고 제출·승인·활성화한다.
--   같은 코드의 ACTIVE가 없으므로 적용일 제한과 승계 기록 없이 기존 계약까지 그 판이 적용된다.
--
-- 데이터 영향
--   db/demo를 함께 적용한 DB(현재 개발·스테이징·운영 배포 설정 전부)는 v1이 이미 RETIRED라 바뀌는 행이 없다.
--   db/migration만 적용한 DB는 이 한 행이 RETIRED가 되고, 새 판을 내기 전까지 상태별 지급조건 판단은
--   FGC-IMPL-POLICY_MISSING으로 멈춘다(통과로 보지 않음, 인터페이스정의서 §4-4-1 "#648 구현 계약").
-- ============================================================================
UPDATE fgc.policy_version
   SET status = 'RETIRED'
 WHERE policy_code = 'STATUS-MONTH-RULE-2026'
   AND version_no = 1
   AND status = 'ACTIVE'
   AND created_by IS NULL
   AND approved_by IS NULL;
