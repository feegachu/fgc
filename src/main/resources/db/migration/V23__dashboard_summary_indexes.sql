-- FGC-FUN-057 / IF-API-03 대시보드 요약(GET /api/v1/dashboard/summary) 성능 검증 결과 반영.
-- 60만 건 cap_check, 30만 건 arbitrage_check, 20만 건 exception_case로 EXPLAIN ANALYZE한
-- 결과, 아래 두 쿼리가 월 필터/정렬 컬럼에 인덱스가 없어 전체 테이블을 Seq Scan했다.
--
-- countCapViolation/countCapWarning: date_trunc('month', as_of_date) 필터가 표현식이라
-- 기존 ix_cap_check_contract(contract_id, payment_stage, as_of_date DESC)로는 못 쓴다.
-- WHERE절과 DISTINCT ON의 ORDER BY를 그대로 인덱스 컬럼 순서에 맞춰서, 필터링과 정렬을
-- 한 번에 인덱스로 해결한다(정렬용 별도 Sort 노드 불필요).
-- as_of_date::timestamp 캐스팅은 DashboardMapper.xml의 countCapViolation/countCapWarning
-- 쿼리와 문자 그대로 일치해야 한다(그래야 플래너가 이 인덱스를 쓴다). 캐스팅 없이
-- date_trunc(text, date)를 쓰면 PostgreSQL이 STABLE인 timestamptz 오버로드를 골라
-- "functions in index expression must be marked IMMUTABLE" 오류가 난다.
CREATE INDEX ix_cap_check_month_latest
  ON fgc.cap_check (date_trunc('month', as_of_date::timestamp), contract_id, payment_stage, checked_at DESC, cap_check_id DESC);

-- findRecentExceptions: created_at DESC 정렬에 쓸 인덱스가 없어 exception_case 전체를
-- 읽고 정렬한 뒤 LIMIT 했다. 정렬 컬럼 그대로 인덱스를 만들면 Top-N을 인덱스 순서대로
-- 바로 읽어 LIMIT에서 끊을 수 있다.
CREATE INDEX ix_exception_case_created_at
  ON fgc.exception_case (created_at DESC, exception_case_id DESC);

-- countArbitrageCandidate: 월 필터가 없어 arbitrage_check 전체를 Seq Scan하며
-- result_status를 걸렀다. 다른 필터가 전혀 없는 카운트라 테이블이 커질수록 비용이
-- 그대로 늘어난다 — CANDIDATE 행만 담는 부분 인덱스로 바꿔 테이블 크기와 무관하게
-- 유지되도록 한다(WARNING/CLEAR 등 다른 상태는 인덱스에 안 들어가 크기도 작다).
CREATE INDEX ix_arbitrage_check_candidate
  ON fgc.arbitrage_check (result_status)
  WHERE result_status = 'CANDIDATE';

-- countReconciliationMismatch(reconciliation_run 월 필터+dedup)와 findRecentValidationRuns
-- (run_type 필터+created_at 정렬)는 같은 60만 건 규모 데이터로 확인했을 때도 각각
-- reconciliation_run 3천 행, validation_run 3천 행 규모의 Seq Scan만으로 1ms·0.8ms대라
-- 인덱스를 추가하지 않았다 — 두 테이블 모두 "정산월×지급단계×보험사당 실행 1건(재실행
-- 포함)"·"월당 검증 실행 1~수 건"으로 자연히 작게 유지되는 테이블이라 cap_check·
-- exception_case처럼 무한정 커지지 않는다.
