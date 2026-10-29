-- #647: 승인본의 기간/규칙은 불변. 승계 경계를 별도로 기록해 계약일별 구버전 선택을 보존한다.
CREATE TABLE fgc.policy_succession (
    previous_policy_version_id bigint PRIMARY KEY REFERENCES fgc.policy_version(policy_version_id),
    successor_policy_version_id bigint NOT NULL UNIQUE REFERENCES fgc.policy_version(policy_version_id),
    cutover_date date NOT NULL,
    activated_by bigint NOT NULL REFERENCES fgc.app_user(user_id),
    activated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (previous_policy_version_id <> successor_policy_version_id)
);

CREATE TRIGGER trg_policy_succession_append_only
BEFORE UPDATE OR DELETE ON fgc.policy_succession
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();

-- 종료·활성화·승계 기록을 모두 저장한 최종 상태를 커밋 시 검증한다.
-- activated_at은 서버의 주입 Clock으로 기록한다. 직접 INSERT 권한은 신뢰한 운영 주체로 제한해야 하며,
-- SQL 관리자가 과거 시각을 위조하는 경우까지 방어하는 권한 경계는 이 날짜 제약의 범위 밖이다.
CREATE FUNCTION fgc.check_policy_succession()
RETURNS trigger LANGUAGE plpgsql SET search_path = fgc, pg_temp AS $$
DECLARE
    previous fgc.policy_version%ROWTYPE;
    successor fgc.policy_version%ROWTYPE;
BEGIN
    SELECT * INTO STRICT previous FROM fgc.policy_version WHERE policy_version_id = NEW.previous_policy_version_id FOR SHARE;
    SELECT * INTO STRICT successor FROM fgc.policy_version WHERE policy_version_id = NEW.successor_policy_version_id FOR SHARE;
    IF previous.status <> 'RETIRED' OR successor.status NOT IN ('ACTIVE', 'RETIRED')
       OR previous.policy_code <> successor.policy_code OR previous.policy_type <> successor.policy_type
       OR previous.version_no >= successor.version_no
       OR NEW.cutover_date <> successor.effective_from OR NEW.cutover_date < previous.effective_from
       OR NEW.cutover_date <= (NEW.activated_at AT TIME ZONE 'Asia/Seoul')::date
       OR (previous.effective_to IS NOT NULL AND NEW.cutover_date > previous.effective_to + 1) THEN
        RAISE EXCEPTION 'Invalid contract-date policy succession: % -> %', previous.policy_version_id, successor.policy_version_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_policy_succession_valid
AFTER INSERT ON fgc.policy_succession DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION fgc.check_policy_succession();

-- ACTIVE는 최신 발행본이다. 이 API로 승계된 RETIRED만 과거 계약일에 계속 적용한다.
-- 레거시 RETIRED 전체를 되살리거나 최신 판 번호/LIMIT 1로 중복을 감추지 않는다.
CREATE VIEW fgc.policy_application_version AS
SELECT p.policy_version_id, p.policy_code, p.policy_type, p.version_no, p.status,
       p.effective_from, p.effective_to, p.fee_regime_code, p.basic_document_version,
       LEAST(p.effective_to, s.cutover_date - 1) AS application_to
FROM fgc.policy_version p
LEFT JOIN fgc.policy_succession s ON s.previous_policy_version_id = p.policy_version_id
WHERE p.status = 'ACTIVE'
   OR (p.status = 'RETIRED' AND s.cutover_date > p.effective_from);

COMMENT ON TABLE fgc.policy_succession IS '계약일 기준 정책 승계 경계. 이전 적용은 cutover_date 전날까지, 새 버전은 당일부터. 승인본 원본은 변경하지 않는다';
COMMENT ON VIEW fgc.policy_application_version IS '계산용 정책 적용 구간. 조회 화면의 원본 effective_to와 별개이며 승계된 과거 정책도 포함한다';
