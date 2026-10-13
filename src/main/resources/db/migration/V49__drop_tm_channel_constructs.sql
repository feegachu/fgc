-- ============================================================================
-- V49: TM·방송채널 특례 구조 제거 (SRC-036)
--
-- 왜: FGC 가상 GA는 대면 모집만 하고, TM·방송채널 승인 대리점이 아니다(SRC-036 D-01·D-02).
--     그래서 감독규정 부칙(제2026-3호) 제1조②·제2조 단서·제3조②와
--     제4-32조⑦ 제1호(전화 모집 녹음비)·제2호(방송비)는 FGC 에 적용할 대상이 없다(SRC-022 :919-921, :5094-5100).
--     이 특례를 표현하던 값과 컬럼을 지운다. V1·V10 은 고치지 않는다(SRC-027 D-04).
--
-- 무엇을
--   schedule_header.schedule_regime   'TM_SPECIAL' 제거 (V1 인라인 CHECK)
--   transaction_attribution            'VOICE_RECORDING'·'BROADCAST' 제거 (V10 ck_attribution_exclusion_type)
--   product_offering                   channel_special_rule_yn 컬럼 삭제 (TM·방송 특례 여부 플래그)
--   product·product_offering 테이블 주석  V1 의 "채널별 적용체계" 문구 교체 — 판매채널은 판정 기준이 아니다
--
-- 기존 행이 지운 값을 쓰고 있으면 ADD CONSTRAINT 가 실패한다. 조용히 바꾸지 않는다.
-- 데모 DB 의 TM 시드는 V48(db/demo)이 먼저 지운다.
-- ============================================================================

ALTER TABLE schedule_header DROP CONSTRAINT schedule_header_schedule_regime_check;
ALTER TABLE schedule_header ADD CONSTRAINT schedule_header_schedule_regime_check
    CHECK (schedule_regime IN ('CURRENT', 'FOUR_YEAR_2027', 'SEVEN_YEAR_2029'));

ALTER TABLE transaction_attribution DROP CONSTRAINT ck_attribution_exclusion_type;
ALTER TABLE transaction_attribution ADD CONSTRAINT ck_attribution_exclusion_type
    CHECK (exclusion_type_snapshot IS NULL
           OR exclusion_type_snapshot IN ('NEW_AGENT_SUPPORT', 'COMPLIANCE_3PCT'));

ALTER TABLE product_offering DROP COLUMN channel_special_rule_yn;

COMMENT ON TABLE product IS '보험상품의 안정적인 식별자. 판매버전별 적용체계는 product_offering에서 관리';
COMMENT ON TABLE product_offering IS '상품 판매개시일·기초서류 버전·판매채널·적용 수수료체계. REG-19 적용체계는 판매개시일·기초서류·수수료체계로 판정(판매채널은 대면 하나, SRC-036)';
