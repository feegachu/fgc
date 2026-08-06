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

-- ── 기존 행 보정 ──────────────────────────────────────────────────────────
--   live 는 0행이지만 이미 실행이 쌓인 팀원 DB·운영 DB를 위해 둡니다.
--   제약을 붙이기 "전에" 돌려야 합니다. 순서를 바꾸면 COMPLETED 행에서 적용이 실패합니다.
--
--   ★★ 왜 한 문장이 아니라 두 갈래인가 — 반드시 읽으세요
--     trg_validation_run_finalized(V1:1647)는 BEFORE UPDATE OR DELETE 이고
--     OLD.status='FINALIZED' 이면 무엇을 바꾸든 조건 없이 RAISE 합니다(V1:1640).
--     그래서 COMPLETED 와 FINALIZED 를 한 UPDATE 로 묶으면
--     "확정된 실행이 하나라도 있는 DB"에서 이 마이그레이션이 그냥 실패합니다.
--     ☞ 이 파일의 초판이 실제로 그랬습니다. dev DB가 0행이라 적용 테스트가 못 잡았습니다.
--       0행은 검증이 아닙니다.
--
--   COMPLETED 는 가드 대상이 아닙니다. 평범한 UPDATE 로 끝냅니다.
UPDATE fgc.validation_run
   SET current_step = 8
 WHERE current_step = 0
   AND status = 'COMPLETED';

-- ── FINALIZED 행 보정 : 불변 가드를 같은 트랜잭션 안에서만 잠시 내립니다 ────
--
--   ★ 규제 시스템에서 이것이 통제 위반이 아닌 근거
--     ① 값을 만들어 내지 않습니다. FINALIZED ⇒ 10 은 바로 아래
--        ck_validation_run_step 이 정의하는 항등식입니다. 추정이 아니라 정의입니다.
--        ☞ §3 의 item_code 와 결정적으로 다릅니다. 그쪽은 과거 마스터값을 알 수 없으므로
--          절대 백필하지 않고 멈춥니다. 판단 기준은 "유도 가능한가, 관측된 것인가"입니다.
--     ② 대상은 V7 이 방금 만든 신규 컬럼 하나뿐입니다. 확정 시점에 존재하지도 않던
--        컬럼의 초기값을 채우는 것이라, 확정 당시 기록된 판정 근거는 한 글자도 안 바뀝니다.
--        0 이라는 값도 사람이 기록한 사실이 아니라 ADD COLUMN 의 DEFAULT 부산물입니다.
--     ③ 우회 사실 자체를 audit_log 에 남깁니다.
--        기록 없는 우회가 통제 위반이고, 기록된 우회는 그 자체가 통제입니다.
--        flyway_schema_history 는 "V7이 돌았다"까지만 말해 줍니다.
--     ④ 고칠 행이 없으면 트리거를 아예 건드리지 않습니다(맨 앞 RETURN).
--        정상 경로(대부분의 DB)에서는 이 파일이 가드를 만지지도 않습니다.
--
--   ★ 트리거 이름을 하나만 지정합니다.
--     DISABLE TRIGGER ALL  은 FK 내부 트리거까지 끄고 superuser 를 요구합니다.
--     DISABLE TRIGGER USER 는 trg_validation_run_lifecycle 까지 같이 꺼집니다.
--     필요한 건 1개입니다. (lifecycle 가드는 status 를 안 바꾸는 이 UPDATE 를 원래 통과시킵니다)
--
--   ★ 왜 DISABLE·UPDATE·ENABLE 을 DO 블록 하나에 넣었나
--     Flyway 는 마이그레이션 1개를 1트랜잭션으로 돌리므로 뒤에서 실패하면 DISABLE 도
--     함께 롤백됩니다(tgenabled 변경은 트랜잭션 대상입니다).
--     그런데 누가 이 파일을 psql -f 로 autocommit 상태에서 돌리면 DISABLE 만 커밋되고
--     뒤에서 터져 "가드가 꺼진 DB"가 남습니다. DO 블록은 그 자체가 한 문장이라
--     autocommit 에서도 통째로 원자적입니다.
--     ☞ 수동 실행 시에는 psql --single-transaction 을 쓰세요.
--
--   ★ 이 창(window) 동안 다른 세션이 끼어들 수 있나 — 없습니다
--     ALTER TABLE ... DISABLE TRIGGER 는 SHARE ROW EXCLUSIVE 락을 잡고, 이 락은
--     ROW EXCLUSIVE(INSERT/UPDATE/DELETE)와 충돌하므로 블록이 끝날 때까지 다른 세션은
--     이 표에 쓸 수 없습니다. SELECT 는 영향 없습니다.
--     게다가 tgenabled 변경은 커밋 전까지 다른 세션에 보이지 않고 같은 트랜잭션에서
--     되돌려지므로, "가드가 꺼진 상태"를 관측한 세션은 존재할 수 없습니다.
--     참고: 아래 ADD CONSTRAINT 는 ACCESS EXCLUSIVE(더 센 락)입니다.
--           즉 이 DISABLE 은 동시성 노출을 새로 만들지 않습니다.
--
--   ★ 테이블 소유자 권한이 필요합니다(우리는 fgc 가 소유자이자 접속 롤).
--     소유자가 아닌 롤로 돌리는 DB에서는 권한 오류로 깨끗하게 실패합니다.
--     조용히 잘못되는 것보다 낫습니다.
--
--   ★ 기각한 대안
--     · NOT VALID 로 눈감기 → 확정 실행이 영구히 0/10 으로 보이고, 트리거가 모든 UPDATE 를
--       막으므로 영원히 고칠 수 없습니다. V6 §2-1 이 NOT VALID 를 쓴 건 값을 "알 수 없을"
--       때였습니다. 여기는 바로 아래 CHECK 에 답이 적혀 있습니다.
--     · 읽기 시점 유도(COALESCE(NULLIF(...))) → CHECK 를 느슨하게 만들고 스텝퍼·진행률바·
--       대시보드·확정조건 네 곳이 fallback 을 기억해야 합니다.
--     · ALTER COLUMN ... TYPE ... USING → 테이블 재작성이라 트리거를 안 거치지만,
--       감사자가 grep 할 수 없는 은닉 우회입니다. DISABLE TRIGGER 는 grep 됩니다.
DO $$
DECLARE
  v_fixed bigint;
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM fgc.validation_run
     WHERE status = 'FINALIZED' AND current_step = 0
  ) THEN
    RETURN;   -- ★ 정상 경로. 가드를 건드리지 않고 끝낸다
  END IF;

  ALTER TABLE fgc.validation_run DISABLE TRIGGER trg_validation_run_finalized;

  WITH fixed AS (
    UPDATE fgc.validation_run
       SET current_step = 10
     WHERE status = 'FINALIZED'
       AND current_step = 0
    RETURNING validation_run_id
  )
  INSERT INTO fgc.audit_log
    (action_code, entity_type, entity_id, before_value, after_value, reason)
  SELECT 'MIGRATION_BACKFILL',
         'validation_run',
         f.validation_run_id::text,
         jsonb_build_object('current_step', 0),
         jsonb_build_object('current_step', 10),
         'V7(v2.1.7) 신규 컬럼 current_step 초기값 보정. '
         'FINALIZED 는 10 이라는 것이 ck_validation_run_step 이 강제하는 항등식이므로 추정값이 아니다. '
         '확정 당시 기록된 컬럼은 하나도 바꾸지 않았다. '
         '이 한 문장을 위해 trg_validation_run_finalized 를 같은 트랜잭션 안에서만 껐다 켰다.'
    FROM fixed f;

  GET DIAGNOSTICS v_fixed = ROW_COUNT;

  ALTER TABLE fgc.validation_run ENABLE TRIGGER trg_validation_run_finalized;

  RAISE NOTICE
    'V7: FINALIZED 실행 %건의 current_step 을 10 으로 보정하고 audit_log 에 남겼습니다. 불변 가드는 다시 켜졌습니다.',
    v_fixed;
END;
$$;

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
--    ★ dev DB 는 0행이라 지금 잠그는 것이 가장 쌉니다. 데이터가 쌓인 뒤에는 과거 항목명을
--      복구할 방법이 없습니다(마스터가 이미 바뀌었을 수 있으므로).
--      ☞ 다만 "0행이니까 안전하다"는 이 파일 초판을 망친 가정입니다.
--        이 마이그레이션이 살아남아야 할 DB 들에는 거짓입니다.
--        아래 백필은 행이 있는 DB를 전제로 씁니다.
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

-- ── 기존 행 보정 ──────────────────────────────────────────────────────────
--
--   ★★ 백필의 방향이 §1 과 정반대입니다 — 반드시 읽으세요
--     §1 의 current_step 은 status 에서 확정적으로 유도됩니다(FINALIZED ⇒ 10).
--     그래서 §1 은 가드를 잠시 내리고 채웁니다.
--     여기 item_code·item_name 은 "계산 당시" 마스터 값의 스냅샷입니다.
--     지금 commission_item 을 읽어 채우면, 그 사이 마스터가 바뀐 만큼
--     과거 판정 근거를 현재 값으로 위조하는 것이 됩니다.
--     (commission_item 은 trg_commission_item_updated_at 이 붙은, 실제로 바뀌는 표입니다)
--     확정(FINALIZED)된 실행에 대해서는 그것이 마이그레이션 실패보다 나쁩니다.
--     위조된 증거는 조용하고 되돌릴 수 없고 나중에 구별조차 안 됩니다.
--     실패한 마이그레이션은 시끄럽고 아무것도 바꾸지 않습니다.
--     ☞ 판단 기준은 하나입니다 : 값이 유도 가능한가, 관측된 것인가.
--       유도 가능하면 채운다(§1). 관측된 것이면 멈춘다(여기).
--
--   ★ 왜 NOT VALID CHECK 로 눈감아 주지 않나 (V6 §2-1 과 다른 판단인 이유)
--     V6 가 NOT VALID 를 쓴 대상은 "V2 가 만든 그 한 행"으로 정체·출처·처분이 이미
--     특정되고 팀이 유산으로 남기기로 결정한 행이었습니다.
--     여기는 그런 행이 있는지조차 모릅니다. 있다면 그건 유산이 아니라,
--     애플리케이션이 지금도 스냅샷 없이 증거행을 쓰고 있다는 살아 있는 결함입니다.
--     NOT VALID 는 찾아내야 할 그 신호를 영구히 조용하게 만듭니다.
--
--   ★ 아래 DO 블록은 이 파일 초판이 주석으로만 말하던 것을 코드로 만듭니다.
--     초판에는 "값을 몰래 채우지 말고 담당자 결정을 받아야 합니다"라고 적혀 있었지만,
--     실제 동작은 트리거 두 겹 안쪽에서 나오는 알아볼 수 없는 오류였습니다.
--     코드가 배반하는 주석은 없는 주석보다 나쁩니다.
DO $$
DECLARE
  v_item  bigint;
  v_month bigint;
BEGIN
  SELECT count(*) FILTER (WHERE d.item_code IS NULL OR d.item_name IS NULL),
         count(*) FILTER (WHERE d.schedule_line_id IS NOT NULL AND d.contract_month_no IS NULL)
    INTO v_item, v_month
    FROM fgc.cap_check_detail d
   WHERE (d.item_code IS NULL
          OR d.item_name IS NULL
          OR (d.schedule_line_id IS NOT NULL AND d.contract_month_no IS NULL))
     AND EXISTS (
           SELECT 1
             FROM fgc.cap_check c
             JOIN fgc.validation_run v ON v.validation_run_id = c.validation_run_id
            WHERE c.cap_check_id = d.cap_check_id
              AND v.status = 'FINALIZED');

  IF v_item > 0 OR v_month > 0 THEN
    RAISE EXCEPTION
      'V7 중단 : 확정(FINALIZED) 실행의 cap_check_detail 에 스냅샷 없는 행이 있습니다 (item_code/item_name %건, contract_month_no %건). 이 값은 되살릴 수 없습니다 — 지금 마스터를 읽어 채우면 과거 판정 근거를 위조합니다. 마이그레이션은 채우지 않고 멈춥니다(아무것도 바뀌지 않았습니다).',
      v_item, v_month
      USING HINT =
        '대상 조회: SELECT d.cap_check_detail_id, v.validation_run_id, v.validation_month, v.run_no, d.item_code, d.item_name, d.schedule_line_id, d.contract_month_no FROM fgc.cap_check_detail d JOIN fgc.cap_check c USING (cap_check_id) JOIN fgc.validation_run v USING (validation_run_id) WHERE v.status = ''FINALIZED'' AND (d.item_code IS NULL OR d.item_name IS NULL OR (d.schedule_line_id IS NOT NULL AND d.contract_month_no IS NULL)); ★ 이것은 감사 결함입니다. 담당자 결정(해당 실행 재검증 또는 예외 승인 기록) 없이 V7 을 적용하지 마세요. 트리거를 끄고 채우는 것은 답이 아닙니다.';
  END IF;
END;
$$;

-- 확정되지 않은 실행(및 run 이 없는 실시간 검증)의 행만 채웁니다.
--
--   ★ COALESCE 를 쓰는 이유
--     초판은 WHERE 가 "둘 중 하나라도 NULL"인데 SET 은 "둘 다"였습니다.
--     item_name 만 비어 있는 행에서 제대로 저장돼 있던 과거 item_code 가
--     현재 마스터 값으로 조용히 덮였습니다.
--     스냅샷을 지키려는 문장이 스냅샷을 파괴하고 있었던 셈입니다.
--     (UPDATE 의 SET 우변 d.item_code 는 갱신 전 값입니다)
--
--   ★ validation_run 을 JOIN 하지 않고 NOT EXISTS 를 쓰는 이유 — 함정입니다
--     cap_check.validation_run_id 는 NULL 허용입니다(REALTIME 검증은 run 이 없습니다).
--     JOIN 해서 v.status <> 'FINALIZED' 로 쓰면 run 없는 상세행이 조인에서 탈락해
--     NULL 로 남고, 바로 아래 SET NOT NULL 이 실패합니다.
--     고치려는 문장에서 같은 실패를 다시 만드는 것입니다.
--     NOT EXISTS 는 그 행을 포함합니다. 그리고 이 술어는
--     guard_finalized_validation_result 가 실제로 검사하는 집합과 정확히 같습니다
--     (그 함수도 NULL run_id 를 array_remove 로 걸러 통과시킵니다).
--     그래서 이 UPDATE 는 트리거에 "걸릴 리 없는" 것이 아니라 구조적으로 걸릴 수 없습니다.
--
--   ★ 이 백필 뒤에 NULL 이 남지 않는 근거 (SET NOT NULL 성공 보장)
--     ① cap_check_detail.commission_item_id 는 NOT NULL + FK → 조인이 반드시 1행 매칭됩니다.
--     ② commission_item.item_code·item_name 은 둘 다 NOT NULL 입니다.
--     ③ 따라서 조인이 닿는 행은 전부 채워지고, 남는 NULL 은 위에서 제외한
--        "확정 실행의 행"뿐입니다. 그런 행이 있으면 위 DO 블록이 이미 멈췄으므로
--        여기까지 오지 못합니다.
--
--   ★ ALTER TABLE(SET NOT NULL / ADD CONSTRAINT)은 행 트리거를 발동시키지 않습니다.
--     트리거에 노출되는 것은 아래 UPDATE 두 개뿐이라, 범위를 좁힐 곳도 여기뿐입니다.
UPDATE fgc.cap_check_detail d
   SET item_code = COALESCE(d.item_code, i.item_code),
       item_name = COALESCE(d.item_name, i.item_name)
  FROM fgc.commission_item i
 WHERE i.commission_item_id = d.commission_item_id
   AND (d.item_code IS NULL OR d.item_name IS NULL)
   AND NOT EXISTS (
         SELECT 1
           FROM fgc.cap_check c
           JOIN fgc.validation_run v ON v.validation_run_id = c.validation_run_id
          WHERE c.cap_check_id = d.cap_check_id
            AND v.status = 'FINALIZED');

-- ★ contract_month_no 도 채웁니다 — 초판이 빠뜨린 세 번째 실패 지점입니다.
--   V5 는 세 컬럼을 모두 nullable 로 추가했으므로, V5 이전에 들어간 행 중
--   schedule_line_id 가 있는 행은 contract_month_no 가 NULL 입니다.
--   그 상태로 아래 ck_cap_detail_month_snapshot 을 (NOT VALID 아닌) 그냥 붙이면
--   그 DB에서 V7 적용이 실패합니다. item_code 와 똑같은 실패 유형입니다.
--
--   이건 위조가 아닙니다 : schedule_line_id 는 FK 라 그 스케줄행이 그대로 살아 있고,
--   contract_month_no 는 그 행 자체의 속성(schedule_line 에서 NOT NULL)이라
--   "다른 값을 끌어오는" 것이 아니라 같은 행에서 다시 읽는 것입니다.
--   그래도 확정 실행은 손대지 않습니다 — 위 DO 블록이 이미 그런 DB를 세웁니다.
UPDATE fgc.cap_check_detail d
   SET contract_month_no = s.contract_month_no
  FROM fgc.schedule_line s
 WHERE s.schedule_line_id = d.schedule_line_id
   AND d.contract_month_no IS NULL
   AND NOT EXISTS (
         SELECT 1
           FROM fgc.cap_check c
           JOIN fgc.validation_run v ON v.validation_run_id = c.validation_run_id
          WHERE c.cap_check_id = d.cap_check_id
            AND v.status = 'FINALIZED');

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
--
-- ⑫ 불변 가드가 다시 켜졌는가 (★ ENABLE 을 빠뜨렸을 때 유일하게 잡아내는 검사)
--    SELECT tgname, tgenabled FROM pg_trigger
--     WHERE tgrelid = 'fgc.validation_run'::regclass AND NOT tgisinternal ORDER BY 1;
--    → trg_validation_run_finalized  O
--      trg_validation_run_lifecycle  O        ← 'D' 가 나오면 즉시 실패
--
-- ⑬ 우회 후에도 불변성이 실제로 복구됐는가 (아래가 실패해야 정상)
--    UPDATE fgc.validation_run SET failure_message='x' WHERE status='FINALIZED';
--    → ERROR: Finalized validation run N is immutable
--
-- ⑭ 우회가 증거를 남겼는가 (FINALIZED 행을 보정한 DB 에서만 행이 있다)
--    SELECT entity_id, before_value, after_value, reason FROM fgc.audit_log
--     WHERE action_code = 'MIGRATION_BACKFILL' AND entity_type = 'validation_run';
--
-- ⑮ COALESCE 가 살아 있는가 (★ 누가 "단순화"하면 이것만 잡아낸다)
--    비확정 실행의 상세행에 item_code='LEGACY_CODE', item_name=NULL 을 넣고 V7 을 적용하면
--    item_code 는 'LEGACY_CODE' 그대로 남고 item_name 만 채워져야 정상이다.
--    둘 다 현재 마스터 값으로 바뀌면 스냅샷을 파괴하는 회귀다.
-- ============================================================================
