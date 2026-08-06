-- ============================================================================
-- FGC 무결성 보강 마이그레이션 v2.1.6 → v2.1.7
-- 대상 DBMS: PostgreSQL 17
-- 작성 기준일: 2026-08-06
-- 결정 근거: SRC-027 FGC 정합성 설계결정서 · VRUN 10단계 정합성 감사
--
-- ── 왜 V1~V6 를 고치지 않고 V7 을 만드나 ──────────────────────────────────
--   앞의 마이그레이션은 이미 팀원 DB에 적용돼 있습니다.
--   적용된 파일을 한 글자라도 고치면 Flyway 가 체크섬 오류를 내고 그 사람 앱이 안 뜹니다.
--
--   ★ 이 파일은 db/migration 에 둡니다. db/demo 가 아닙니다.
--     운영은 classpath:db/migration 만 읽고, 로컬만 db/demo 를 더 읽습니다.
--     따라서 V7 은 V3·V4 시드가 있다고 가정하면 안 됩니다.
--
-- ── 고치는 것 3가지 ───────────────────────────────────────────────────────
--   1) 월 통합검증 10단계 진행률을 저장할 자리가 없는 문제
--          → validation_run.current_step 신설
--   2) 실행(run)을 처음부터 FINALIZED 로 INSERT 할 수 있는 문제  ★ V6 누락 보완
--          → guard_run_lifecycle 에 INSERT 분기 추가
--   3) V5 가 만든 스냅샷 컬럼이 NULL 을 허용해 목적을 우회할 수 있는 문제
--          → NOT NULL + 조건부 CHECK
--
-- ── 데이터 영향 ───────────────────────────────────────────────────────────
--   컬럼 1개·제약 3개를 추가하고 함수 1개를 확장합니다.
--   기존 행을 지우지 않습니다. 값 보정(백필)은 아래 두 곳뿐이며 모두 멱등입니다.
-- ============================================================================

SET search_path TO fgc, public;


-- ============================================================================
-- 1. 월 통합검증 10단계 진행률을 담을 자리
--
--    무엇이 문제였나
--      운영정책서 제43조는 월 통합검증을 10단계로 정합니다.
--        1 실행생성 2 대상선별 3 스케줄 4 1,200% 5 차익거래
--        6 원장기표·균형 7 양방향대사 8 예외생성 | 9 담당자검토 10 확정
--      1~8 은 Spring Batch(MonthlyValidationJob), 9~10 은 사람이 합니다.
--
--      그런데 이 10단계를 저장할 컬럼이 어디에도 없었습니다.
--      VRUN-W02 스텝퍼, VRUN-W01·DASH-W01 진행률 바(step/10*100),
--      확정조건 1번(step >= 8)이 전부 없는 값을 읽고 있었고,
--      MVP 는 localStorage 로 위장하고 있었습니다.
--
--    ★ status 5개와 10단계는 서로 다른 축입니다. 그 자체는 정상 설계입니다.
--        status      = 실행의 거친 생명주기 (CREATED→RUNNING→COMPLETED→FINALIZED)
--        current_step = RUNNING 안에서의 진행 위치
--      문제는 두 번째 축을 저장하지 않았다는 것이었습니다.
--
--    ★ 왜 Spring Batch 메타테이블(batch_step_execution)에서 읽지 않나
--      ① V2 헤더(36-39행)가 "배치 메타테이블을 업무 로직이 읽는 구조"를
--         이미 명시적으로 거부했습니다. 업무용 값은 업무 스키마에 둡니다.
--      ② 배치 Step 8개가 정책 10단계와 1:1이 아닙니다.
--         6단계 = Step 2개(journalPostingStep→imbalanceCheckStep),
--         4단계 = 파티션 2개, 7단계 = 2회 실행.
--      ③ 9·10단계는 사람이 합니다. Spring Batch 에 Step 자체가 없어
--         구조적으로 표현할 수 없습니다.
--      ④ validation_run 에 job_execution_id 가 없어 조인할 키도 없습니다.
--
--    ★ 왜 단계별 테이블이 아니라 컬럼 1개인가
--      화면이 실제로 쓰는 것은 스칼라 하나입니다.
--      스텝퍼는 s.no <= current_step 으로 색만 칠하고,
--      진행률 바는 current_step/10*100 을 씁니다.
--      IF-API-49 가 약속한 readCount·writeCount 는 쓰는 화면이 없습니다.
--      (인터페이스정의서 스펙을 이 현실에 맞춰 축소합니다)
--
--    ★ 9단계에 별도 값을 두지 않는 이유
--      status='COMPLETED' AND current_step=8 이 곧 "배치 끝, 사람 검토 대기"입니다.
--      스텝퍼가 1~8 초록 / 9·10 회색으로 그려지고, 그것이 정확히 그 뜻입니다.
--      MVP 도 8 에서 10 으로 건너뜁니다. 값을 하나 더 만들면 누가 9로 올리는지
--      정하는 규칙이 필요해지는데, 그 규칙에 해당하는 사용자 행동이 화면에 없습니다.
--
--    reconciliation_run 에는 넣지 않습니다.
--    10단계는 월 통합검증 고유 개념이고 대사 실행에는 스텝퍼 화면이 없습니다.
-- ============================================================================
ALTER TABLE fgc.validation_run
  ADD COLUMN IF NOT EXISTS current_step smallint NOT NULL DEFAULT 0;

COMMENT ON COLUMN fgc.validation_run.current_step IS
  '월 통합검증 10단계 중 현재 위치(운영정책서 제43조). 0=시작 전, 1~8=배치, 9~10=사람. '
  '진행률 = current_step / 10 * 100. status=FAILED 이면 이 값이 실패한 단계다. '
  '★ 9단계(담당자 검토)에 별도 값을 두지 않는다 — status=COMPLETED + current_step=8 이 '
  '"배치 끝, 사람 검토 대기"를 뜻한다. 단계 이름은 DB가 아니라 화면 상수가 갖는다.';

-- 기존 행 보정. live 는 0행이지만 이미 실행이 쌓인 팀원 DB를 위해 둡니다.
-- 제약을 붙이기 "전에" 돌려야 합니다. 순서를 바꾸면 COMPLETED 행에서 적용이 실패합니다.
UPDATE fgc.validation_run
   SET current_step = CASE status WHEN 'FINALIZED' THEN 10 ELSE 8 END
 WHERE current_step = 0
   AND status IN ('COMPLETED','FINALIZED');

ALTER TABLE fgc.validation_run DROP CONSTRAINT IF EXISTS ck_validation_run_step;
ALTER TABLE fgc.validation_run ADD CONSTRAINT ck_validation_run_step CHECK (
     (status = 'CREATED'             AND current_step = 0)
  OR (status IN ('RUNNING','FAILED') AND current_step BETWEEN 0 AND 8)
  OR (status = 'COMPLETED'           AND current_step = 8)
  OR (status = 'FINALIZED'           AND current_step = 10)
);

COMMENT ON CONSTRAINT ck_validation_run_step ON fgc.validation_run IS
  'status 와 current_step 이 서로 어긋나지 않게 한다. 핵심은 두 가지 — '
  'current_step=10 은 FINALIZED 일 때만 가능하고(돌지 않은 검증이 확정된 것처럼 보이지 않게), '
  'FINALIZED 는 반드시 10 이다. RUNNING·FAILED 를 0~8 로 느슨하게 둔 것은 의도적이다 — '
  '실행 직후 RUNNING+0 을 지나가므로 여기를 조이면 얻는 것 없이 정상 흐름을 막는다.';


-- ============================================================================
-- 2. 실행 INSERT 우회 차단  ★ V6 누락 보완
--
--    무엇이 문제였나
--      V6 는 policy_version 의 INSERT 우회를 막았지만(guard_policy_version_lifecycle)
--      실행 테이블에는 같은 조치를 하지 않았습니다.
--      trg_validation_run_lifecycle 과 trg_reconciliation_run_lifecycle 이
--      BEFORE UPDATE 뿐이라, 아래가 지금은 그냥 통과합니다.
--
--        INSERT INTO fgc.validation_run
--          (validation_month, run_no, status, finalized_at)
--        VALUES (DATE '2026-08-01', 1, 'FINALIZED', now());
--
--      ck_validation_finalized(V1:1078)도 finalized_at 만 요구해서 막지 못합니다.
--      돌지도 않은 검증이 확정 상태로 태어나면 그 확정에는 근거가 없습니다.
--      V6 가 상태 "전이"는 막았는데 상태를 가진 채 "태어나는" 경로를 놓친 것입니다.
--
--    ★ INSERT 분기에서 즉시 RETURN 하므로 아래쪽 to_jsonb(OLD) 가 NULL 을 만지지 않습니다.
--      (INSERT 트리거에서 OLD 는 존재하지 않습니다)
--
--    시드·코드 영향 — 확인했습니다.
--      V3·V4 에 validation_run / reconciliation_run INSERT 자체가 없습니다.
--      Java 에도 실행을 생성하는 코드가 없습니다(ValidationRunController 미구현).
--      current_step 도 DEFAULT 0 이라 CREATED 조건과 자동으로 맞습니다.
-- ============================================================================
--   ★★ SET search_path 를 함수 정의에 인라인으로 붙이는 이유 — 반드시 읽으세요
--     CREATE OR REPLACE FUNCTION 은 함수 수준 SET 옵션을 "초기화"합니다.
--     V6 가 맨 뒤 DO 루프로 이 함수에 search_path 를 박아 뒀는데,
--     여기서 CREATE OR REPLACE 를 하면 그 설정이 조용히 날아갑니다.
--     (실제로 이번에 그렇게 날아갔고, 적용 후 확인 ⑪에서 잡았습니다)
--
--     V6 는 루프를 파일 맨 뒤에 둬서 이 문제를 피했습니다. V7 은 루프가 없으므로
--     정의에 직접 씁니다. 이렇게 하면 다음 사람이 이 함수를 또 교체할 때도 눈에 보입니다.
--
--     ☞ 앞으로 fgc 의 함수를 CREATE OR REPLACE 하는 마이그레이션은 반드시
--       (a) 정의에 SET search_path 를 인라인으로 쓰거나
--       (b) 파일 맨 뒤에서 V6 §5 의 핀 루프를 다시 돌려야 합니다.
--       적용 후 확인 ⑪ 로 검증하세요.
CREATE OR REPLACE FUNCTION fgc.guard_run_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
BEGIN
  -- v2.1.7 신규 : INSERT 우회 차단. 실행은 반드시 CREATED 로 태어난다.
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'CREATED' THEN
      RAISE EXCEPTION
        '% must be inserted as CREATED (got %); advance it with UPDATE',
        TG_TABLE_NAME, NEW.status;
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
      (OLD.status = 'CREATED'   AND NEW.status = 'RUNNING') OR
      (OLD.status = 'RUNNING'   AND NEW.status IN ('COMPLETED','FAILED')) OR
      (OLD.status = 'COMPLETED' AND NEW.status = 'FINALIZED') OR
      (OLD.status = 'FAILED'    AND NEW.status = 'RUNNING')
    ) THEN
      RAISE EXCEPTION 'Invalid % status transition: % -> % (run %)',
        TG_TABLE_NAME, OLD.status, NEW.status, to_jsonb(OLD) ->> TG_ARGV[0];
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fgc.guard_run_lifecycle() IS
  'validation_run·reconciliation_run 공용 생명주기 가드. '
  'INSERT 는 CREATED 만 허용(v2.1.7). '
  '전이 허용 : CREATED→RUNNING, RUNNING→COMPLETED, RUNNING→FAILED, COMPLETED→FINALIZED, FAILED→RUNNING. '
  '트리거 인자로 PK 컬럼명을 넘긴다(오류 메시지 전용). '
  'FINALIZED 이후 불변성은 기존 guard_*_run_finalized 트리거가 담당한다(중복 구현 금지)';

-- 트리거를 INSERT 까지 확장. 이름은 그대로 두어 기존 실행 순서(finalized < lifecycle)를 지킵니다.
DROP TRIGGER IF EXISTS trg_validation_run_lifecycle ON fgc.validation_run;
CREATE TRIGGER trg_validation_run_lifecycle
BEFORE INSERT OR UPDATE ON fgc.validation_run
FOR EACH ROW EXECUTE FUNCTION fgc.guard_run_lifecycle('validation_run_id');

DROP TRIGGER IF EXISTS trg_reconciliation_run_lifecycle ON fgc.reconciliation_run;
CREATE TRIGGER trg_reconciliation_run_lifecycle
BEFORE INSERT OR UPDATE ON fgc.reconciliation_run
FOR EACH ROW EXECUTE FUNCTION fgc.guard_run_lifecycle('reconciliation_run_id');


-- ============================================================================
-- 3. V5 스냅샷 컬럼 잠금
--
--    무엇이 문제였나
--      V5 는 cap_check_detail 에 item_code·item_name·contract_month_no 를 추가해
--      "검증 당시 항목명"을 스냅샷으로 남기게 했습니다. 목적은 정확합니다 —
--      나중에 마스터가 바뀌어도 과거 판정 근거가 그때 값으로 남아야 합니다(CAP-W02).
--
--      그런데 세 컬럼 모두 NULL 을 허용해서, 값을 넣지 않아도 행이 저장됩니다.
--      즉 애플리케이션이 V5 의 목적을 조용히 우회할 수 있었습니다.
--
--    ★ 지금이 유일하게 공짜인 시점입니다.
--      cap_check_detail 이 0행입니다. 데이터가 쌓인 뒤에는 과거 항목명을
--      복구할 방법이 없습니다(마스터가 이미 바뀌었을 수 있으므로).
--
--    ★ contract_month_no 는 왜 무조건 NOT NULL 이 아닌가
--      ck_cap_detail_source(V1:1140-1142)가 transaction_attribution_id 와
--      schedule_line_id 중 하나만 허용합니다.
--      귀속행(실제 지급건) 출처의 상세행에는 "회차"라는 개념이 없습니다.
--      그래서 schedule_line 이 있는 행에만 조건부로 강제합니다.
--
--    ★ 읽기 쪽 Java 도 함께 고쳐야 합니다.
--      CapCheckDetailLine.java 의 contractMonthNo 가 primitive int 였습니다.
--      귀속행 출처 행을 조회하면 MyBatis 가 NULL 을 언박싱하며 터집니다.
--      이 마이그레이션과 같은 커밋에서 Integer 로 바꿉니다.
-- ============================================================================

-- 기존 행 보정. live 는 0행입니다.
--   ⚠️ cap_check_detail 에는 trg_cap_check_detail_immutable 이 걸려 있어,
--      FINALIZED 실행에 속한 행이 있으면 이 UPDATE 가 거부됩니다.
--      그런 DB에서는 아래 마이그레이션이 실패합니다. 그때는 먼저 확인하세요:
--        SELECT d.cap_check_detail_id, v.validation_run_id, v.status
--          FROM fgc.cap_check_detail d
--          JOIN fgc.cap_check c USING (cap_check_id)
--          JOIN fgc.validation_run v USING (validation_run_id)
--         WHERE (d.item_code IS NULL OR d.item_name IS NULL);
--      FINALIZED 실행의 상세행이 이미 스냅샷 없이 저장돼 있다면 그 자체가
--      감사 결함입니다. 값을 몰래 채우지 말고 담당자 결정을 받아야 합니다.
UPDATE fgc.cap_check_detail d
   SET item_code = i.item_code,
       item_name = i.item_name
  FROM fgc.commission_item i
 WHERE i.commission_item_id = d.commission_item_id
   AND (d.item_code IS NULL OR d.item_name IS NULL);

ALTER TABLE fgc.cap_check_detail
  ALTER COLUMN item_code SET NOT NULL,
  ALTER COLUMN item_name SET NOT NULL;

ALTER TABLE fgc.cap_check_detail DROP CONSTRAINT IF EXISTS ck_cap_detail_month_snapshot;
ALTER TABLE fgc.cap_check_detail ADD CONSTRAINT ck_cap_detail_month_snapshot CHECK (
  schedule_line_id IS NULL OR contract_month_no IS NOT NULL
);

COMMENT ON CONSTRAINT ck_cap_detail_month_snapshot ON fgc.cap_check_detail IS
  '예상 스케줄에서 온 상세행은 계산 당시 회차를 반드시 스냅샷한다. '
  '귀속행(실제 지급건) 출처는 회차 개념이 없어 NULL 이 정당하다 — '
  '읽는 쪽 DTO 는 primitive 가 아니라 Integer 여야 한다(CapCheckDetailLine).';

COMMENT ON COLUMN fgc.cap_check_detail.item_code IS
  '계산 당시 commission_item.item_code 스냅샷. NOT NULL(v2.1.7) — '
  '이후 항목 코드가 바뀌어도 판정 근거는 그대로 남는다(CAP-W02)';
COMMENT ON COLUMN fgc.cap_check_detail.item_name IS
  '계산 당시 commission_item.item_name 스냅샷. NOT NULL(v2.1.7)';
COMMENT ON COLUMN fgc.cap_check_detail.contract_month_no IS
  '계산 당시 schedule_line.contract_month_no 스냅샷. '
  'schedule_line 출처면 필수, 귀속행 출처면 NULL(ck_cap_detail_month_snapshot)';


-- ============================================================================
-- 적용 후 확인
-- ============================================================================
-- ① 실행을 FINALIZED 로 직삽 (실패해야 정상)
--    INSERT INTO fgc.validation_run (validation_month, run_no, status, finalized_at)
--    VALUES (date_trunc('month', current_date)::date, 901, 'FINALIZED', now());
--    → ERROR: validation_run must be inserted as CREATED (got FINALIZED)
--
-- ② 대사 실행도 같은지 (실패해야 정상)
--    INSERT INTO fgc.reconciliation_run (settlement_month, payment_stage, status)
--    VALUES (date_trunc('month', current_date)::date, 'GA_TO_FC', 'RUNNING');
--    → ERROR: reconciliation_run must be inserted as CREATED (got RUNNING)
--
-- ③ 정상 생성 후 기본값 (CREATED / 0 이어야 정상)
--    INSERT INTO fgc.validation_run (validation_month, run_no)
--    VALUES (date_trunc('month', current_date)::date, 901);
--    SELECT status, current_step FROM fgc.validation_run WHERE run_no = 901;
--    → CREATED | 0
--
-- ④ 10단계 정상 진행 (전부 성공해야 정상)
--    UPDATE fgc.validation_run SET status='RUNNING', current_step=1, started_at=now() WHERE run_no=901;
--    UPDATE fgc.validation_run SET current_step=8, status='COMPLETED', completed_at=now() WHERE run_no=901;
--    UPDATE fgc.validation_run SET status='FINALIZED', current_step=10, finalized_at=now() WHERE run_no=901;
--
-- ⑤ 확정인데 단계가 8 (실패해야 정상)
--    → ck_validation_run_step 위반
--
-- ⑥ RUNNING 인데 단계가 10 (실패해야 정상)
--    UPDATE fgc.validation_run SET current_step=10 WHERE run_no=901 AND status='RUNNING';
--    → ck_validation_run_step 위반
--    (정리) DELETE FROM fgc.validation_run WHERE run_no=901;   -- FINALIZED 면 삭제도 막힙니다
--
-- ⑦ 스냅샷 없이 상세행 저장 (실패해야 정상)
--    item_code / item_name 을 빼고 INSERT → NOT NULL 위반
--
-- ⑧ 스케줄 출처인데 회차 없음 (실패해야 정상)
--    schedule_line_id 를 넣고 contract_month_no 를 비우고 INSERT
--    → ck_cap_detail_month_snapshot 위반
--
-- ⑨ 귀속행 출처이고 회차 없음 (★ 성공해야 정상)
--    transaction_attribution_id 를 넣고 contract_month_no 를 비우고 INSERT
--    → 성공. 귀속행에는 회차 개념이 없다
--
-- ⑩ V6 회귀 — 아래 세 개가 여전히 막혀야 정상
--    · 정책을 ACTIVE 로 직삽 → 'must be inserted as DRAFT'
--    · 실행 상태 점프 CREATED→FINALIZED → 'Invalid ... transition'
--    · 처리기록 UPDATE → 'is append-only'
--
-- ⑪ search_path 가 안 박힌 함수 (★ IMMUTABLE 3개만 나와야 정상)
--    SELECT proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
--     WHERE n.nspname='fgc' AND p.prokind='f' AND p.proconfig IS NULL ORDER BY 1;
--    → is_first_day_of_month / is_within_first_year / round_krw_half_up
--
--    이 목록에 guard_* 가 나오면 그 함수를 CREATE OR REPLACE 하면서
--    SET search_path 를 잃은 것입니다. 위 §2 의 ★★ 주석을 보세요.
--    함수를 교체하는 마이그레이션마다 이 쿼리를 돌리는 것이 가장 확실합니다.
-- ============================================================================
