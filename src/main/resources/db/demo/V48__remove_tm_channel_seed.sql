-- ============================================================================
-- V48: TM 채널 시드 제거 (SRC-036)
--
-- 왜: 2026-10-13 팀 결정으로 FGC 가상 GA는 대면 모집만 한다(SRC-036 D-01·D-02).
--     V4 가 만든 TM 판매버전 STD-TERM-A '2026-TM-A' 와 그 계약
--     G3 FGC-FGL03-202608-0002 는 더 이상 시나리오가 아니다.
--     V4 는 이미 적용됐으므로 고치지 않고 여기서 지운다(SRC-027 D-04).
--
-- 범위: TM 판매버전에 걸린 계약의 상태이벤트·재무스냅샷·계약, 그리고 판매버전.
--     V4 시드 기준으로 G3 에 딸린 행은 상태이벤트 1건(ACTIVE)과 재무스냅샷 1건(버전 1)뿐이다.
--     로컬 DB 에서 G3 로 스케줄·검증·분개를 만들었다면 FK 가 이 마이그레이션을 막는다
--     — 그 경우 로컬 DB 를 다시 만든다(데모 시드는 test·local 에만 적용된다).
--
-- 방법: contract_status_event 는 append-only 트리거가 DELETE 를 막으므로
--     이 삭제 동안만 끈다(V23_1 선례). 재무스냅샷 트리거는 후속 버전이 없는
--     버전 1 행의 삭제를 허용한다(V44).
-- ============================================================================

CREATE TEMP TABLE tmp_tm_contract ON COMMIT DROP AS
SELECT c.contract_id
  FROM insurance_contract c
  JOIN product_offering po ON po.product_offering_id = c.product_offering_id
 WHERE po.channel_code = 'TM';

ALTER TABLE contract_status_event DISABLE TRIGGER trg_contract_status_event_append_only;
DELETE FROM contract_status_event WHERE contract_id IN (SELECT contract_id FROM tmp_tm_contract);
ALTER TABLE contract_status_event ENABLE TRIGGER trg_contract_status_event_append_only;

DELETE FROM contract_financial_snapshot WHERE contract_id IN (SELECT contract_id FROM tmp_tm_contract);
DELETE FROM insurance_contract WHERE contract_id IN (SELECT contract_id FROM tmp_tm_contract);
DELETE FROM product_offering WHERE channel_code = 'TM';
