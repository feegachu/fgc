-- ============================================================================
-- V60(demo): STD-LIFE-B 2026-CURRENT-B 판매종료일 채우기 (FUN-014-01, #534)
--
-- 왜: 2026-CURRENT-B 는 판매종료일이 비어 있어 2027-FOUR-YEAR(2027-01-01~)와 판매기간이 겹친다.
--     다음 마이그레이션(V61)이 같은 상품·채널의 판매기간 겹침을 DB 제약으로 막으므로, 제약보다 앞 번호에서
--     옛 판매버전을 새 판매개시일 전날(2026-12-31)로 닫는다. 기존 V3·V4 는 고치지 않는다(SRC-027 D-04).
-- ============================================================================
UPDATE product_offering po
   SET sales_end_date = DATE '2026-12-31'
  FROM product p
 WHERE p.product_id = po.product_id
   AND p.standard_product_code = 'STD-LIFE-B'
   AND po.offering_version = '2026-CURRENT-B'
   AND po.sales_end_date IS NULL;
