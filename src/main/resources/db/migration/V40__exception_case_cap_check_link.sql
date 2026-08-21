-- #331 — 확정 차단 건의 계산근거로 갈 수 있게 exception_case 에 cap_check 포인터를 남긴다.
--
-- 배경
--   CapCheckMapper 의 목록 두 곳(latestScopedCapChecks · findLatestByContractAndStage)은
--   확정 거절된 DRAFT 후보의 cap_check 를 candidate_transaction_id 조건으로 제외한다.
--   주석이 그 대안을 명시한다 — "지급 확정이 거절된 DRAFT 후보의 계산근거는 예외함에서만 보존한다".
--   그런데 실시간 경로(FUN-034, CapExceptionMapper.insertException)는 source_entity 에
--   COMMISSION_TRANSACTION:{paymentId} 를 넣고 capCheckId 는 버려 왔다. 그래서 예외에서
--   계산근거로 갈 ID 가 시스템 어디에도 남지 않았고, 시연 컷⑥("왜 차단됐는지 계산근거 열기")이
--   성립하지 않았다.
--
--   IF-API-31(CapCheckMapper.findById)은 제외 조건이 없어 거절된 건에도 정상 동작한다 —
--   팝업은 이미 열 수 있고 가리킬 ID 만 없었다.
--
-- source_entity 를 CAP_CHECK 로 바꾸지 않고 컬럼을 따로 두는 이유
--   exception_case 에는 payment_id 컬럼이 없어, 실시간 경로에서 지급 건을 가리키는 유일한 자리가
--   source_entity 다. 여기를 CAP_CHECK 로 덮으면 "이 위반이 어느 지급 시도에서 나왔는가" 를
--   잃는다 — 규제 위반 예외에서 감사상 계산근거만큼 중요한 정보다. 둘 다 보존한다.
--
-- 배치 경로(ExceptionCaseMapper)는 이미 source_entity 에 CAP_CHECK:{cap_check_id} 를 넣으므로
-- 이 컬럼 없이도 계산근거를 찾을 수 있다. 화면은 컬럼을 우선 보고 없으면 source_entity 로
-- 물러선다(ExceptionCaseListRow.capCheckReference).

ALTER TABLE fgc.exception_case
    ADD COLUMN cap_check_id bigint;

COMMENT ON COLUMN fgc.exception_case.cap_check_id IS
    '판정 근거가 된 cap_check. 실시간 확정 경로(FUN-034)가 채운다. 배치 경로는 source_entity 로 가리킨다.';

ALTER TABLE fgc.exception_case
    ADD CONSTRAINT exception_case_cap_check_id_fkey
    FOREIGN KEY (cap_check_id) REFERENCES fgc.cap_check (cap_check_id);

-- 예외함에서 계산근거를 여는 조회만 쓰므로 부분 인덱스로 충분하다.
CREATE INDEX ix_exception_case_cap_check
    ON fgc.exception_case (cap_check_id)
    WHERE cap_check_id IS NOT NULL;
