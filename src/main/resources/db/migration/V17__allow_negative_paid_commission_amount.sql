-- 확정 지급액보다 환수·차감액이 큰 경우 확정수수료 순액은 음수가 될 수 있다.
-- REG-12 차익거래 산식에 실제 순액을 반영할 수 있도록 비음수 제약을 제거한다.
ALTER TABLE fgc.arbitrage_check
    DROP CONSTRAINT IF EXISTS arbitrage_check_paid_commission_amount_check;
