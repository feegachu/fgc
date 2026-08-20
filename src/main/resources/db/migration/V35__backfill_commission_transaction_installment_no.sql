-- 2026-08-19 hjKang - 실제 지급 건 회차(installment_no) 소급 채움
-- 기존 코드: V18이 컬럼을 만들었지만 값을 쓰는 경로가 없어 시드·화면 등록 건이 모두 NULL로 남았다.
-- 문제: 대사 매처(GaFc·InsurerGaReconciliationMatcher)가 예상·실제가 모두 있는 그룹에서도
--       회차를 비교하지 못해 전 그룹이 REVIEW_REQUIRED로 떨어진다. 확정된 시드 행은
--       불변성 트리거 때문에 애플리케이션 경로로는 고칠 수 없다.
-- 개선: 확정 불변성 트리거를 백필 구간에서만 중지하고(V9__commission_payment_natural_key.sql 과 같은 패턴),
--       ① 운영 스케줄이 있으면 그 회차를, ② 없으면 단일 회차 정책규칙의 회차를 채운다.
--       어느 쪽으로도 단일 값이 나오지 않으면 NULL로 남긴다 — V18 컬럼 주석의 계약 그대로다.

-- 확정 지급 건 불변성 트리거는 운영 변경을 막기 위한 것이므로,
-- 백필 구간에서만 중지하고 같은 Flyway 트랜잭션에서 즉시 복구한다.
ALTER TABLE fgc.commission_transaction
    DISABLE TRIGGER trg_commission_transaction_guard;
ALTER TABLE fgc.commission_transaction
    DISABLE TRIGGER trg_commission_transaction_installment_guard;

-- ① 운영 스케줄 기준 — 이미 배치를 돌려 schedule_line 이 있는 DB에서 동작한다.
--    관리자수수료처럼 같은 항목·같은 월에 수취인만 다른 행이 여럿이어도
--    회차가 하나로 수렴하면 채운다. 대응 스케줄을 못 찾은 귀속행이 하나라도 있으면 건너뛴다.
WITH resolved AS (
    SELECT ta.commission_transaction_id,
           MIN(sl.installment_no)                             AS installment_no,
           COUNT(DISTINCT sl.installment_no)                  AS variant_count,
           COUNT(*) FILTER (WHERE sl.installment_no IS NULL)  AS unresolved_count
      FROM fgc.transaction_attribution ta
      JOIN fgc.commission_transaction ct
        ON ct.commission_transaction_id = ta.commission_transaction_id
      LEFT JOIN fgc.schedule_header sh
        ON sh.contract_id      = ta.contract_id
       AND sh.payment_stage    = ct.payment_stage
       AND sh.schedule_purpose = 'OPERATIONAL'
       AND sh.active_yn        = TRUE
       AND sh.status NOT IN ('HOLD', 'CANCELLED')
      LEFT JOIN fgc.schedule_line sl
        ON sl.schedule_header_id = sh.schedule_header_id
       AND sl.commission_item_id = ct.commission_item_id
       AND sl.due_month          = ta.attribution_month
       AND sl.line_status <> 'CANCELLED'
     WHERE ta.attribution_scope = 'CONTRACT'
     GROUP BY ta.commission_transaction_id
)
UPDATE fgc.commission_transaction ct
   SET installment_no = r.installment_no
  FROM resolved r
 WHERE ct.commission_transaction_id = r.commission_transaction_id
   AND ct.installment_no IS NULL
   AND r.variant_count    = 1
   AND r.unresolved_count = 0;

-- ② 정책규칙 기준 폴백 — 새로 만든 DB는 이 마이그레이션 시점에 schedule_line 이 비어 있다.
--    (V3__seed_demo_data.sql 은 스케줄을 넣지 않는다. 스케줄은 배치가 나중에 만든다.)
--    적용 회차 범위가 한 회차로 고정된 규칙(installment_from = installment_to)은
--    그 항목의 지급이 반드시 그 회차분이므로 스케줄 없이도 회차를 확정할 수 있다.
--    MAINTENANCE_COMMISSION(2~12회차) 같은 범위 규칙과 현행 규칙이 없는 항목
--    (COMMON_COST·SETTLEMENT_SUPPORT·NEWCOMER_SUPPORT)은 여기서 제외되어 NULL로 남는다.
WITH single_installment_item AS (
    SELECT cr.payment_stage,
           cr.commission_item_id,
           MIN(cr.installment_from) AS installment_no
      FROM fgc.commission_rule cr
      JOIN fgc.policy_version pv
        ON pv.policy_version_id = cr.policy_version_id
     WHERE pv.status = 'ACTIVE'
       AND cr.installment_from = cr.installment_to
     GROUP BY cr.payment_stage, cr.commission_item_id
    HAVING COUNT(DISTINCT cr.installment_from) = 1
)
UPDATE fgc.commission_transaction ct
   SET installment_no = s.installment_no
  FROM single_installment_item s
 WHERE ct.installment_no     IS NULL
   AND ct.payment_stage      = s.payment_stage
   AND ct.commission_item_id = s.commission_item_id
   AND ct.cashflow_type      = 'PAYMENT';

ALTER TABLE fgc.commission_transaction
    ENABLE TRIGGER trg_commission_transaction_installment_guard;
ALTER TABLE fgc.commission_transaction
    ENABLE TRIGGER trg_commission_transaction_guard;
