-- ============================================================================
-- V61: 같은 상품·채널의 판매버전 판매기간 겹침 배제 (FUN-014-01, #534, 운영정책서 제45조·REG-19)
--
-- 왜: 판매종료일이 빈 옛 판매버전은 판매기간 검사를 통과한다. 그래서 계약이 옛 판매버전에 잘못 연결돼도
--     현행 체계로 계산될 수 있다. 같은 상품·채널에서 판매기간이 겹치는 판매버전을 DB에서 막는다.
--     새 판매버전을 등록하려면 이전 판매버전의 판매종료일을 새 판매개시일 전날로 먼저 닫아야 한다.
--
-- 제약은 DEFERRABLE INITIALLY IMMEDIATE 다: 운영에서는 INSERT/UPDATE 마다 즉시 검사하고, 같은 상품에 기준일이 같은
-- 판매버전을 일부러 여러 개 만드는 롤백 테스트만 SET CONSTRAINTS 로 검사를 미룬다.
--
-- 운영 데이터를 조용히 고치지 않는다: 겹치는 행이 있으면 판매버전 ID 와 함께 마이그레이션이 실패한다.
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS btree_gist;

DO $$
DECLARE
  v_overlaps text;
BEGIN
  SELECT string_agg(a.product_offering_id || '(' || a.offering_version || ') x '
                    || b.product_offering_id || '(' || b.offering_version || ')', ', ' ORDER BY a.product_offering_id)
    INTO v_overlaps
    FROM fgc.product_offering a
    JOIN fgc.product_offering b
      ON b.product_id = a.product_id
     AND b.channel_code = a.channel_code
     AND b.product_offering_id > a.product_offering_id
     AND daterange(a.sales_start_date, a.sales_end_date, '[]') && daterange(b.sales_start_date, b.sales_end_date, '[]');

  IF v_overlaps IS NOT NULL THEN
    RAISE EXCEPTION 'Overlapping product_offering sales periods must be closed before V61: %', v_overlaps;
  END IF;
END
$$;

ALTER TABLE fgc.product_offering
    ADD CONSTRAINT ex_product_offering_sales_period EXCLUDE USING gist (
        product_id WITH =,
        channel_code WITH =,
        daterange(sales_start_date, sales_end_date, '[]') WITH &&
    ) DEFERRABLE INITIALLY IMMEDIATE;
COMMENT ON CONSTRAINT ex_product_offering_sales_period ON fgc.product_offering IS
    '같은 상품·채널의 판매버전 판매기간은 겹칠 수 없다. 새 판매버전 등록 전에 이전 판매버전의 판매종료일을 먼저 닫는다';
