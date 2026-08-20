-- 2026-08-20 yslee - FUN-052 원장 정정 예외의 미종결 업무건을 DB에서도 하나로 제한
-- 기존 코드: exception_key 전체 유일성만 있어 생명주기 순번이 다른 활성 예외의 중복을 DB가 막지 못함
-- 문제: 서비스 외 생성 경로나 향후 어댑터가 추가되면 같은 원분개에 NEW/IN_REVIEW가 둘 이상 생길 수 있음
-- 개선: 기존 exception_case를 유지하고 원분개·정책버전별 활성 JOURNAL_CORRECTION_REQUIRED만 부분 UNIQUE 처리
CREATE UNIQUE INDEX uq_exception_active_journal_correction
    ON fgc.exception_case (
        source_entity_type,
        source_entity_id,
        exception_type,
        COALESCE(policy_version_id, -1)
    )
    WHERE source_entity_type = 'JOURNAL_HEADER'
      AND exception_type = 'JOURNAL_CORRECTION_REQUIRED'
      AND status IN ('NEW', 'IN_REVIEW');
