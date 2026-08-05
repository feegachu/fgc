-- ============================================================================
-- FGC 보정 마이그레이션 v2.1.4 -> v2.1.5
-- 대상 DBMS: PostgreSQL 17
--
-- 왜 필요한가
--   cap_check_detail은 commission_item_id/schedule_line_id로만 항목을 참조했다.
--   그래서 IF-API-31(계산근거 팝업)이 항목명을 보여주려면 commission_item/schedule_line을
--   "지금" 다시 조인해서 읽어야 했는데, 이러면 나중에 상품/스케줄이 바뀔 때 과거 CAP 판정의
--   계산근거가 그때 값이 아니라 최신 값으로 바뀌어 보인다 — CAP-W02 "검증 당시 저장된 스냅샷을
--   그대로 보여준다" 요구사항 위반이다 (PR #2 코드리뷰 지적).
--
--   계산 시점의 item_code/item_name/contract_month_no를 cap_check_detail 자체에 스냅샷으로
--   저장해 이 문제를 없앤다. evidence_ref는 이미 있는 컬럼이라 손대지 않는다.
-- ============================================================================

ALTER TABLE fgc.cap_check_detail
  ADD COLUMN item_code varchar(50),
  ADD COLUMN item_name varchar(120),
  ADD COLUMN contract_month_no integer;

COMMENT ON COLUMN fgc.cap_check_detail.item_code IS '계산 당시 commission_item.item_code 스냅샷 — 이후 항목 코드가 바뀌어도 판정 근거는 그대로 남는다';
COMMENT ON COLUMN fgc.cap_check_detail.item_name IS '계산 당시 commission_item.item_name 스냅샷';
COMMENT ON COLUMN fgc.cap_check_detail.contract_month_no IS '계산 당시 schedule_line.contract_month_no 스냅샷';
