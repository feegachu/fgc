-- FGC-FUN-043 결과 집계 — schedule_header에 검증 실행 스코프를 붙인다.
-- 기존 코드: schedule_header는 validation_run_id가 없어 "이번 실행에서 생성·재생성한
-- 스케줄" 건수를 실행 단위로 셀 방법이 없었다. 유일하게 남는 흔적은 Spring Batch의
-- batch_step_execution.write_count뿐인데, FUN-043은 그 메타테이블 역산을 명시적으로
-- 금지한다.
-- 개선: nullable FK를 추가한다. 월 검증 배치(regenerateScheduleStep)가 생성·재생성한
-- 헤더에만 채우고, 계약 생성·정책 변경 등 검증 실행과 무관한 경로에서 만든 헤더는
-- NULL로 남긴다(그 경로들은 이 컬럼을 채우지 않는다).
ALTER TABLE fgc.schedule_header
    ADD COLUMN validation_run_id bigint REFERENCES fgc.validation_run(validation_run_id);

CREATE INDEX idx_schedule_header_validation_run ON fgc.schedule_header (validation_run_id);
