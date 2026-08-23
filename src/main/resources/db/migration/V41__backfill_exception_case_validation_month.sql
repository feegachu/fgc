-- #330: 실시간 확정 경로(validation_run_id NULL)로 생성된 예외는 validation_month 가
-- 비어 있어 월 통합검증 확정 게이트(제44조)가 세지 못했다.
-- 이후 생성분은 CapExceptionMapper·CommissionPaymentMapper INSERT 가 지급 건의
-- 정산월을 함께 남기므로, 여기서는 기존 행만 지급 건(source_entity)으로 역추적해 채운다.
-- source_entity_id 가 varchar 라 숫자 캐스팅 오류가 없도록 텍스트 쪽으로 조인한다.
UPDATE fgc.exception_case ec
   SET validation_month = ct.settlement_month
  FROM fgc.commission_transaction ct
 WHERE ec.validation_month IS NULL
   AND ec.source_entity_type = 'COMMISSION_TRANSACTION'
   AND ec.source_entity_id = CAST(ct.commission_transaction_id AS varchar);
