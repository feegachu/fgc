-- SRC-032 후속 보정 (PR #197 리뷰 반영. develop 병합으로 V27이 선점되어 V29로 이동)
-- 1) 검증원장 업무키에 분개 원천 식별자를 추가한다 — 같은 실행에서 같은 계약·분개유형의
--    서로 다른 불균형 분개가 한 업무건으로 합쳐져 두 번째 건의 증거가 소실되던 문제.
--    (런타임 키는 ExceptionCaseMapper.insertFromJournalImbalances 가 같은 형식으로 생성)
-- 2) V23_1 이관 ELSE 분기가 남긴 레거시 DATA_QUALITY 키의 'INSURANCE_CONTRACT' 세그먼트를
--    런타임 규칙('CONTRACT')으로 보정한다 — 재검출 시 기존 업무건에 붙지 못하고
--    중복 생성되던 문제 (FGC-FUN-052 인수조건). V23_1의 CAP 계산 실패 분기(코드리뷰
--    반영분)는 이미 ':CONTRACT:{계약}:{지급단계}' 형식이라 이 보정의 대상이 아니다.
-- ※ record_exception_detection 의 v_evidence_changed NULL 가드는 develop 의 V24 가
--    함수 본문에서 이미 처리한다(이력 없음 → 증거 변경으로 간주) — 여기서 재정의하지 않는다.

-- 1. 검증원장 업무키 전진 보정. 이미 원천 식별자가 붙었거나 보정 키가 선점된 행은 건너뛴다.
UPDATE fgc.exception_case ec
   SET exception_key = CONCAT(ec.exception_key, ':', jh.source_entity_id)
  FROM fgc.journal_header jh
 WHERE ec.exception_type = 'JOURNAL_IMBALANCE'
   AND ec.source_entity_type = 'JOURNAL_HEADER'
   AND ec.source_entity_id = CAST(jh.journal_header_id AS varchar)
   AND ec.exception_key NOT LIKE CONCAT('%:', jh.source_entity_id)
   AND NOT EXISTS (
       SELECT 1
         FROM fgc.exception_case other
        WHERE other.exception_key = CONCAT(ec.exception_key, ':', jh.source_entity_id)
   );

-- 2. 레거시 DATA_QUALITY(원천 INSURANCE_CONTRACT) 키 세그먼트 보정.
--    구 CAP_CALCULATION_FAILED 건 중 지급단계 차원이 키에 없던 시절 값은 완전 재구성이
--    불가능하다 — 그 건은 다음 검출 때 새 키의 업무건으로 1회 갈라진 뒤 수렴한다.
UPDATE fgc.exception_case ec
   SET exception_key = REPLACE(ec.exception_key, ':INSURANCE_CONTRACT:', ':CONTRACT:')
 WHERE ec.source_entity_type = 'INSURANCE_CONTRACT'
   AND ec.exception_key LIKE '%:INSURANCE_CONTRACT:%'
   AND NOT EXISTS (
       SELECT 1
         FROM fgc.exception_case other
        WHERE other.exception_key = REPLACE(ec.exception_key, ':INSURANCE_CONTRACT:', ':CONTRACT:')
   );
