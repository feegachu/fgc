-- 2026-08-10 yslee - REG-11 제외유형 어휘와 상태 정합성 강제
-- 기존 코드: EXCLUDED 귀속행을 비표준 MANUAL_EXCLUSION 값으로 저장하고 DB 제약이 없음
-- 문제: 녹취·방송·신인활동지원·준법경영비를 구분할 수 없어 증빙과 감사 근거를 재현할 수 없음
-- 개선: 기존 불명확 행은 REVIEW_REQUIRED로 전환하고 허용 유형 및 EXCLUDED 상태 관계를 CHECK로 강제

-- 2026-08-10 yslee - 확정 지급 건의 코드 표준화를 위한 마이그레이션 구간에서만 불변 트리거 일시 해제
-- 기존 코드: 확정된 지급 건의 귀속행은 정상 애플리케이션 요청에서 수정할 수 없도록 트리거가 차단
-- 문제: 데이터 표준화 마이그레이션도 같은 트리거에 막혀 V10 적용 자체가 실패함
-- 개선: 동일 트랜잭션 안에서 트리거를 해제하고 데이터 이관 후 즉시 다시 활성화
ALTER TABLE fgc.transaction_attribution
    DISABLE TRIGGER trg_transaction_attribution_guard;

UPDATE fgc.transaction_attribution
   SET inclusion_status_snapshot = 'REVIEW_REQUIRED',
       exclusion_type_snapshot = NULL
 WHERE exclusion_type_snapshot = 'MANUAL_EXCLUSION';

-- 2026-08-10 yslee - 기존 신인활동지원비 제외유형을 규제조문표 표준 코드로 이관
-- 기존 코드: 데모 및 기존 데이터는 NEWCOMER_SUPPORT를 제외유형으로 저장
-- 문제: 규제조문표 v0.2.1의 표준 코드 NEW_AGENT_SUPPORT와 달라 신규 CHECK 제약조건을 적용할 수 없음
-- 개선: 의미가 같은 기존 코드를 NEW_AGENT_SUPPORT로 변환한 뒤 표준 코드만 허용
UPDATE fgc.transaction_attribution
   SET exclusion_type_snapshot = 'NEW_AGENT_SUPPORT'
 WHERE exclusion_type_snapshot = 'NEWCOMER_SUPPORT';

ALTER TABLE fgc.transaction_attribution
    ENABLE TRIGGER trg_transaction_attribution_guard;

ALTER TABLE fgc.transaction_attribution
    ADD CONSTRAINT ck_attribution_exclusion_type
    CHECK (
        exclusion_type_snapshot IS NULL
        OR exclusion_type_snapshot IN (
            'VOICE_RECORDING',
            'BROADCAST',
            'NEW_AGENT_SUPPORT',
            'COMPLIANCE_3PCT'
        )
    );

ALTER TABLE fgc.transaction_attribution
    ADD CONSTRAINT ck_attribution_exclusion_status
    CHECK (
        (inclusion_status_snapshot = 'EXCLUDED' AND exclusion_type_snapshot IS NOT NULL)
        OR
        (inclusion_status_snapshot <> 'EXCLUDED' AND exclusion_type_snapshot IS NULL)
    );
