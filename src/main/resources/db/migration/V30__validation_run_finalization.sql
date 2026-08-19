-- FGC-FUN-044-02 / IF-API-51 확정 메타데이터
-- Idempotency-Key는 인터페이스 공통 규칙 2-5에 따라 선택이며, 제공된 경우에만 재요청 판정에 사용한다.

ALTER TABLE fgc.validation_run
    ADD COLUMN finalize_idempotency_key varchar(160);

-- NULL은 여러 행에 허용하되 실제로 전달된 키는 전체 실행에서 한 번만 사용하게 한다.
-- 서비스 사전 조회는 친절한 VRUN_006 응답용이고 이 인덱스가 동시 요청의 최종 방어선이다.
CREATE UNIQUE INDEX uq_validation_run_finalize_idempotency
    ON fgc.validation_run (finalize_idempotency_key)
    WHERE finalize_idempotency_key IS NOT NULL;

COMMENT ON COLUMN fgc.validation_run.finalize_idempotency_key IS
    'IF-API-51 성공 요청의 선택 Idempotency-Key. 키를 제공한 동일 요청은 최초 확정 결과를 반환';
