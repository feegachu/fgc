-- #948: 차익거래 검토대상은 위반 확정이 아닌 주의 건이다.
-- 완료된 과거 처리 기록을 보존하고 현재 미처리 건의 심각도를 새 검출 기준에 맞춘다.
UPDATE fgc.exception_case
   SET severity = 'WARNING', updated_at = CURRENT_TIMESTAMP
 WHERE exception_type = 'ARBITRAGE_CANDIDATE'
   AND status IN ('NEW', 'IN_REVIEW')
   AND severity <> 'WARNING';
