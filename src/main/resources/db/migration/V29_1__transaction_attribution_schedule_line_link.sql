-- #39: 증빙 필수 제외 판정은 수수료 항목 전체가 아니라 실제 지급 귀속행이 대응하는
-- 운영 예상 스케줄의 개별 발생건(schedule_line) 기준으로만 판단한다.
ALTER TABLE fgc.transaction_attribution
    ADD COLUMN schedule_line_id bigint
        REFERENCES fgc.schedule_line(schedule_line_id) ON DELETE RESTRICT;

CREATE INDEX ix_transaction_attribution_schedule_line
    ON fgc.transaction_attribution(schedule_line_id)
    WHERE schedule_line_id IS NOT NULL;

COMMENT ON COLUMN fgc.transaction_attribution.schedule_line_id IS
    '실제 지급 귀속행이 대응하는 운영 예상 스케줄 행. 증빙 필수 제외는 이 발생건 단위로만 인정한다.';
