-- 2026-08-20 yslee - FUN-053 오탐·반려 예외의 재검토 조치 코드 추가
-- 기존 코드: exception_action.action_type CHECK가 최초 검토와 종결 조치만 허용
-- 문제: REJECTED 오조작을 감사이력과 사유를 남기면서 IN_REVIEW로 되돌릴 수 없음
-- 개선: REOPEN을 허용하되 상태 전이 검증은 애플리케이션 상태머신에서 REJECTED 전용으로 제한
ALTER TABLE fgc.exception_action
    DROP CONSTRAINT exception_action_action_type_check;

ALTER TABLE fgc.exception_action
    ADD CONSTRAINT exception_action_action_type_check
        CHECK (action_type IN (
            'ASSIGN', 'START_REVIEW', 'CORRECT', 'REDUCE', 'CANCEL', 'DEFER',
            'RECONCILE_AGAIN', 'FALSE_POSITIVE', 'RESOLVE', 'REJECT', 'REOPEN', 'COMMENT'
        ));
