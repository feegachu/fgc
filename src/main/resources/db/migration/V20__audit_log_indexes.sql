-- 2026-08-16 yslee - FUN-061 감사로그 조회(IF-API-52) 인덱스 추가
-- 기존 코드: audit_log 는 INSERT 전용 기록만 있고 인덱스가 PK 뿐
-- 문제: AUDT-W01 조회 API 의 기간·대상·행위자 필터와 occurred_at DESC 정렬이 전부 풀스캔
-- 개선: 조회 패턴(무필터 최신순 / 대상종류+ID / 행위자)별 복합 인덱스 3개 추가.
--       action_code 는 카디널리티가 낮고 항상 기간 필터와 결합되므로 단독 인덱스를 만들지 않는다.

CREATE INDEX idx_audit_log_occurred_at ON fgc.audit_log (occurred_at DESC);
CREATE INDEX idx_audit_log_entity ON fgc.audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_log_user ON fgc.audit_log (user_id, occurred_at DESC);
