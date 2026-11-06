-- 화면에 보이는 시드 문구에서 팀 내부 말을 뺀다: 규정, 출처 코드, 이슈 번호, 데모, 시연, 룰셋, 원수사, 시나리오 번호.
--
-- 범위: 사람이 읽는 문구만 고친다. 정책코드와 enum, 규정 참조(regulation_refs, source_refs)는 그대로 두고
--       화면에서 이름으로 바꾼다(frontend features/policies/management.ts refLabel, labels.ts policySeedText).
-- 방법: 적용된 마이그레이션은 고칠 수 없어 여기서 고친다. 승인 정책과 확정 지급은 불변 트리거가 막으므로
--       이 갱신 동안만 끈다(V49 선례). 계약, 금액, 판정은 건드리지 않는다.
-- 조건: demo-agent(V72) 행이 없는 서버도 있어 문구가 같은 행만 바꾼다. 없으면 0건이다.
SET search_path TO fgc, public;

-- 정책 이름
ALTER TABLE policy_version DISABLE TRIGGER trg_policy_version_lifecycle;
UPDATE policy_version pv
   SET policy_name = names.new_name
  FROM (VALUES
    ('가상 GA 표준 환수율(데모)', '표준 환수율'),
    ('하늘가상생명 환수율(시연 가정)', '하늘가상생명 환수율'),
    ('푸른가상생명 환수율(시연 가정)', '푸른가상생명 환수율'),
    ('다온가상손해보험 환수율(시연 가정)', '다온가상손해보험 환수율'),
    ('예상 해약환급률표(시연 상품 보강)', '예상 해약환급률표(비교 상품)'),
    ('예상 해약환급률표(연금저축 보강)', '예상 해약환급률표(연금저축)'),
    ('대사 허용오차 정책(1차 0원)', '대사 허용오차 정책(0원)'),
    ('원수사→GA 현행 수입기준', '보험회사→GA 현행 수입기준'),
    ('원수사→GA 초년도 1,200% 룰셋', '보험회사→GA 초년도 1,200% 한도'),
    ('원수사→GA 초년도 1,200% 룰셋(준법경영비 공제 시행 전, 보정판)', '보험회사→GA 초년도 1,200% 한도(준법경영비 공제 시행 전)'),
    ('원수사→GA 초년도 1,200% 룰셋(준법경영비 3% 공제 포함)', '보험회사→GA 초년도 1,200% 한도(준법경영비 3% 공제 포함)'),
    ('GA→설계사 초년도 1,200% 룰셋', 'GA→설계사 초년도 1,200% 한도'),
    ('GA→FC 차익거래 적용 시작일', 'GA→설계사 차익거래 적용 시작일')
  ) AS names(old_name, new_name)
 WHERE pv.policy_name = names.old_name;
ALTER TABLE policy_version ENABLE TRIGGER trg_policy_version_lifecycle;

-- 정책 상세의 설정 규칙 설명
ALTER TABLE policy_parameter DISABLE TRIGGER trg_policy_parameter_policy_lock;
UPDATE policy_parameter pp
   SET description = texts.new_text
  FROM (VALUES
    ('일할계산하지 않는다. 유지관리수수료는 매월 동일 금액이어야 한다 (REG-03)', '일할 계산하지 않습니다. 유지관리수수료는 매월 같은 금액이어야 합니다.'),
    ('부활해도 실효 기간에 건너뛴 회차를 소급 지급하지 않는다. 그 기간에는 유지관리 서비스가 없었다 (REG-03)', '부활해도 실효 기간에 건너뛴 회차를 소급 지급하지 않습니다. 그 기간에는 유지관리 서비스가 없었습니다.'),
    ('그 회차 지급예정일 시점에 계약이 유효했으면 지급한다. 대사 매칭키도 회차+지급예정일이라 검증과 대사가 같은 기준을 쓴다', '그 회차 지급예정일에 계약이 유효했으면 지급합니다. 대사도 회차와 지급예정일을 기준으로 비교합니다.'),
    ('대사 허용오차(원). 1차는 0원 — 1원만 달라도 불일치로 본다', '대사 허용오차(원). 0원이라 1원만 달라도 불일치입니다.'),
    ('대사 허용오차(일). 1차는 0일 — 지급예정일이 하루만 달라도 불일치로 본다', '대사 허용오차(일). 0일이라 지급예정일이 하루만 달라도 불일치입니다.'),
    ('기간 경계일 포함 여부: 데모는 3주년 당일 제외, 정밀 경계는 프로젝트 해석', '기간 경계일 포함 여부: 3주년 당일은 제외합니다.'),
    ('GA→FC 판정은 이 날짜 이상 체결된 신계약에 적용한다.', 'GA→설계사 판정은 이 날짜 이상 체결된 신계약에 적용합니다.')
  ) AS texts(old_text, new_text)
 WHERE pp.description = texts.old_text;
ALTER TABLE policy_parameter ENABLE TRIGGER trg_policy_parameter_policy_lock;

-- 항목별 산입 판정의 판단 이유. 문장은 두고 괄호 안 코드만 뺀다.
ALTER TABLE cap_rule_item DISABLE TRIGGER trg_cap_rule_item_policy_lock;
UPDATE cap_rule_item
   SET decision_reason = regexp_replace(decision_reason, '\s*\(REG-[^)]*\)', '', 'g')
 WHERE decision_reason ~ '\(REG-';
ALTER TABLE cap_rule_item ENABLE TRIGGER trg_cap_rule_item_policy_lock;

ALTER TABLE cap_check_detail DISABLE TRIGGER trg_cap_check_detail_immutable;
UPDATE cap_check_detail
   SET decision_reason = regexp_replace(decision_reason, '\s*\(REG-[^)]*\)', '', 'g')
 WHERE decision_reason ~ '\(REG-';
UPDATE cap_check_detail SET item_name = '기본수수료' WHERE item_name = 'FC 기본수수료';
ALTER TABLE cap_check_detail ENABLE TRIGGER trg_cap_check_detail_immutable;

-- 수수료 항목 이름. FC는 화면 다른 곳에서 설계사로 부른다.
UPDATE commission_item SET item_name = '기본수수료' WHERE item_code = 'BASE_COMMISSION' AND item_name = 'FC 기본수수료';

-- 해약환급률표 출처
ALTER TABLE refund_rate_table DISABLE TRIGGER trg_refund_rate_table_policy_lock;
UPDATE refund_rate_table
   SET source_document_ref = '보험회사 예상 해약환급률표 2026 (예시값)'
 WHERE source_document_ref LIKE '원수사 예상 해약환급률표 2026 (합성값%';
ALTER TABLE refund_rate_table ENABLE TRIGGER trg_refund_rate_table_policy_lock;

-- 근거 정보 팝오버의 규정 이름
UPDATE policy_reference SET title = '분급 적용례·규칙 판정' WHERE ref_code = 'REG-19' AND title = '분급 적용례·룰셋 판정';

-- 지급 건 비고. 시나리오 번호만 있는 메모는 지우고, 말이 되는 것은 문장만 남긴다.
-- 수정 시각은 업무 변경이 아니라 그대로 둔다.
ALTER TABLE commission_transaction DISABLE TRIGGER trg_commission_transaction_guard;
ALTER TABLE commission_transaction DISABLE TRIGGER trg_commission_transaction_updated_at;
UPDATE commission_transaction
   SET note = CASE
                WHEN note LIKE '%공통비 안분' THEN '공통비 안분'
                WHEN note LIKE '%관리자 합산' THEN '관리자 합산'
                WHEN note = 'R3 나머지 미지급' THEN '나머지 미지급'
              END
 WHERE note ~ '^[RA][0-9]( |$)' OR note LIKE '#475 %';
UPDATE commission_transaction
   SET note = '경력설계사 정착지원금 2026-07 지급분'
 WHERE note = '경력설계사 정착지원금 2026-07 월 지급분. 그 달 신계약에 안분 (REG-20)';
ALTER TABLE commission_transaction ENABLE TRIGGER trg_commission_transaction_updated_at;
ALTER TABLE commission_transaction ENABLE TRIGGER trg_commission_transaction_guard;

-- 귀속 내역의 증빙 칸
ALTER TABLE transaction_attribution DISABLE TRIGGER trg_transaction_attribution_guard;
UPDATE transaction_attribution
   SET evidence_ref = NULL
 WHERE evidence_ref ~ '^[RA][0-9]( |$)' OR evidence_ref LIKE '#475 %';
UPDATE transaction_attribution
   SET evidence_ref = '등록일 2026-07-01, 직전 3년 경력 조회 2026-06-30'
 WHERE evidence_ref = '등록일 2026-07-01 · 직전3년 경력조회 2026-06-30 · 신인 지원기준 GA-NEW-2026 v1';
ALTER TABLE transaction_attribution ENABLE TRIGGER trg_transaction_attribution_guard;
