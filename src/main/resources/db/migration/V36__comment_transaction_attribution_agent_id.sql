-- 2026-08-19 hjKang - transaction_attribution.agent_id 의 지급단계별 의미를 컬럼 주석으로 고정
-- 기존 코드: V1 baseline 이 테이블 주석만 남기고 agent_id 의 뜻은 어디에도 적지 않았다.
-- 문제: 이 컬럼은 지급단계에 따라 뜻이 다르다. GA_TO_GA 가 아니라 GA_TO_FC 에서는 "이 돈을 받은 사람",
--       INSURER_TO_GA 에서는 "이 계약을 모집한 사람"이다. 원수사→GA 는 받는 주체가 GA 법인이라
--       개인 수취인이 없는데(ck_transaction_recipient), 대사는 원수사 명세의 설계사코드 매핑 결과가
--       계약 모집설계사와 같은지를 검증한다(시드명세 REC-06). 이 구분이 문서화되지 않아
--       "원수사→GA 에 왜 설계사가 붙어 있냐"는 오해로 값을 비우거나 검증을 제거할 위험이 있다.
-- 개선: 규칙을 컬럼 주석으로 남긴다. V3 시드의 COALESCE(수취인, 계약 모집설계사)와
--       CommissionPaymentServiceImpl.resolveAttributionAgentId 가 같은 규칙을 구현한다.

COMMENT ON COLUMN fgc.transaction_attribution.agent_id IS
    '귀속행에 연결된 설계사. 지급단계에 따라 뜻이 다르다 — GA_TO_FC 는 이 돈을 받은 수취인, '
    'INSURER_TO_GA 는 이 계약을 모집한 설계사(수취인 아님. 원수사→GA 의 수취 주체는 GA 법인이다). '
    '수취인이 있으면 수취인을, 없으면 귀속 계약의 모집설계사를 넣는다.';
