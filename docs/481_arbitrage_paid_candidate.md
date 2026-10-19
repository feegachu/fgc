# #481 지급분 기준 차익거래 후보

FUN-063 판정과 net_difference_amount 저장값은 currentExcessAmount(확정 지급·차감 순액 + 포함 환급금 - 납입보험료, 음수는 0) 기준이다.
projectedExcessAmount와 candidateType은 calculation_snapshot에 유지하고 조회 API에서도 별도 제공한다.
지급예정 포함 초과만 있으면 CLEAR이며 예외 업무건을 생성하지 않는다. 기존 저장 이력·예외는 소급 변경하지 않는다.
ARB-W01 목록·상세에서 지급예정 포함 초과를 모니터링 지표로 구분한다.

PLANNED 합산은 검증 기준일 이하 최초 해지·실효 효력일(서울 날짜) 이후 예정일을 제외한다.
효력일 당일 예정액은 포함하고, 아직 효력이 발생하지 않은 미래 사건은 현재 모니터링을 줄이지 않는다.
스케줄 행 자체는 변경하지 않는다. 대시보드는 기존 CANDIDATE 집계가 새 판정을 그대로 반영한다.
#480의 해지 시점 금융자료 선택은 별도 PR이며 두 수정은 함께 적용하는 것이 좋다.

테이블 DDL·마이그레이션 없음. 조회 응답에 projectedExcessAmount·candidateType을 추가한다.
