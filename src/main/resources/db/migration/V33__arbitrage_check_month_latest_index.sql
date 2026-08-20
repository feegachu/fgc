-- #257 시연 점검에서 대시보드 "차익거래 검토대상" 10건과 예외함 ARBITRAGE_CANDIDATE 5건이
-- 어긋났다. 원인은 판정을 쌓는 쪽이 아니라 읽는 쪽이었다 — uq_arbitrage_check가
-- (validation_run_id, contract_id, payment_stage, as_of_date)라 같은 달을 두 번 검증하면
-- (run_no 1·2) 같은 계약의 행이 하나 더 쌓이는데(설계대로다. 인터페이스정의서 7-5 단계표,
-- IF-API-33 수동 재검증도 MANUAL_CONTRACT run을 새로 만든다), DashboardMapper의
-- countArbitrageCandidate가 월 필터도 dedup도 없이 행 수를 그대로 셌다.
-- 예외 키(ARBITRAGE_CANDIDATE:월:CONTRACT:계약:지급단계)는 재실행해도 하나만 남으므로
-- (화면정의서 EXCP-W01 "재실행해도 하나만 생깁니다") 예외함 쪽이 맞고, KPI는
-- 화면정의서 DASH-W01 KPI 표대로 "이번 달 CANDIDATE 계약 수"가 되어야 한다.
--
-- 쿼리를 월 필터 + 계약·지급단계별 최신 1건으로 바꿨으니 인덱스도 그 모양에 맞춘다.
-- WHERE절과 DISTINCT ON의 ORDER BY를 인덱스 컬럼 순서에 그대로 맞춰 필터링과 정렬을
-- 한 번에 인덱스로 해결한다(별도 Sort 노드 불필요) — V23에서 cap_check에 한 것과 같다.
-- as_of_date::timestamp 캐스팅은 DashboardMapper.xml의 countArbitrageCandidate 쿼리와
-- 문자 그대로 일치해야 플래너가 이 인덱스를 쓴다. 캐스팅 없이 date_trunc(text, date)를
-- 쓰면 PostgreSQL이 STABLE인 timestamptz 오버로드를 골라
-- "functions in index expression must be marked IMMUTABLE" 오류가 난다.
CREATE INDEX ix_arbitrage_check_month_latest
  ON fgc.arbitrage_check (date_trunc('month', as_of_date::timestamp),
                          contract_id, payment_stage, as_of_date DESC, arbitrage_check_id DESC);

-- V23의 ix_arbitrage_check_candidate는 "월 필터가 전혀 없는 카운트"를 전제로 만든
-- 부분 인덱스였다. 이제 월 필터가 붙어 위 인덱스가 그 역할을 대신하므로 남겨 둘 이유가 없다.
DROP INDEX IF EXISTS fgc.ix_arbitrage_check_candidate;
