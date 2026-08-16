-- 2026-08-13 yslee - 실제 지급 회차 CHECK 제약의 기존 데이터 검증 분리
-- 기존 코드: V18에서 CHECK 제약 추가와 기존 commission_transaction 행 검증을 한 번에 수행
-- 문제: 대형 원장에서 기존 행 검사 중 강한 테이블 잠금이 길어져 명세 적재와 거래 확정이 지연될 수 있음
-- 개선: V18은 신규·변경 행에 제약을 즉시 적용하고 기존 행 검증은 낮은 잠금 수준의 별도 단계로 수행

ALTER TABLE fgc.commission_transaction
    VALIDATE CONSTRAINT ck_commission_transaction_installment_no;
