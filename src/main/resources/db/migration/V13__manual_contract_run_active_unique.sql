-- ============================================================================
-- FGC 무결성 보강 마이그레이션 v2.1.8 → v2.1.9
-- 대상 DBMS: PostgreSQL 17
-- 작성 기준일: 2026-08-11
-- 결정 근거: #76 DailyChangedContractJob 코드리뷰 — "하루 1건" 멱등성이 DB 제약이 아니라
--            애플리케이션의 check-then-create(조회 후 없으면 생성) 순서로만 지켜지고 있다는
--            지적 (docs/05_인터페이스정의서_v2_0.md:536 "기존 UNIQUE 제약이 그대로 작동"이라는
--            문구와 실제 스키마가 어긋나 있었음)
--
-- ── 왜 필요한가 ───────────────────────────────────────────────────────────
--   validation_run의 유일한 UNIQUE 제약은 uq_validation_run(validation_month, run_no)뿐이다.
--   MONTHLY는 uq_validation_run_active_month(V1)가 "월당 활성(CREATED/RUNNING) 실행 1건"을
--   막아주지만, MANUAL_CONTRACT에는 대응하는 제약이 없다. run_no는 채번마다 새로 매겨지므로
--   두 실행이 동시에 CreateDailyRunTasklet의 조회→생성을 거치면 서로 다른 run_no를 얻어
--   둘 다 INSERT에 성공한다 — 같은 날 활성 MANUAL_CONTRACT 실행이 2건 생길 수 있다.
--
-- ── 왜 uq_validation_run_active_month처럼 "월별"이 아니라 "전체 1건"인가 ──────
--   MONTHLY는 validation_month(대상월)가 업무적으로 의미 있는 스코프라 월별로 막는다.
--   MANUAL_CONTRACT는 대상월 개념이 없다(트리거가 채우는 validationMonth는 그냥 "이번 달"
--   고정값일 뿐, 업무적으로 스코프 역할을 하지 않는다) — 실제로 막아야 하는 건 "지금 이 순간
--   활성인 일일배치 실행이 여러 개 동시에 도는 것" 자체이므로, run_type 전체를 스코프로 삼는다.
--
-- ── 위반 시 동작 ─────────────────────────────────────────────────────────
--   CreateDailyRunTasklet이 이 제약을 사전에 확인하지 않으므로(조회→생성 사이 레이스만
--   막는 최후 방어선), 위반 시 INSERT가 그대로 예외로 실패하고 changedContractStep으로
--   넘어가지 못한 채 Job이 FAILED로 끝난다. 다음 재실행(자동/수동)의 findManualContract
--   RunCreatedBetween 조회가 먼저 성공한 실행을 찾아내 그대로 재사용하므로, 별도 조치 없이
--   재시도만으로 정상화된다(§7-6 공통규칙 3 "재시작은 실패한 Step부터" — 여기서는 Step 1부터
--   재시작하는 것과 동일한 효과).
--
-- ── 데이터 영향 ───────────────────────────────────────────────────────────
--   부분 UNIQUE 인덱스 1개만 추가한다. 기존 행을 지우거나 고치지 않는다.
--   다만 이미 같은 날 활성(CREATED/RUNNING) MANUAL_CONTRACT 실행이 2건 이상 있는 DB에는
--   인덱스 생성 자체가 실패한다 — 그 경우 운영팀이 먼저 중복 실행 중 하나를 FAILED로
--   전이시키거나 종료해야 한다(1차 시연/개발 DB에는 그런 데이터가 없다고 가정).
-- ============================================================================

SET search_path TO fgc, public;

CREATE UNIQUE INDEX IF NOT EXISTS uq_validation_run_active_manual_contract
  ON validation_run (run_type)
  WHERE run_type = 'MANUAL_CONTRACT' AND status IN ('CREATED', 'RUNNING');

COMMENT ON INDEX uq_validation_run_active_manual_contract IS
  'MANUAL_CONTRACT(일일 변경계약 배치) 실행은 한 번에 하나만 활성(CREATED/RUNNING)일 수 있다. '
  'run_type 컬럼 자체를 인덱스 대상으로 쓰는 이유는 WHERE절이 이미 값을 MANUAL_CONTRACT 하나로 '
  '고정해서, 조건을 만족하는 모든 행이 같은 값을 가지므로 "최대 1행"과 같은 뜻이 되기 때문이다.';
