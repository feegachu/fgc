-- ============================================================================
-- V17_1. COMPLIANCE 역할 표시명 보정 — '준법·감사 조회자' → '준법·감사' (#86)
--
--   V17_1 번호를 잡은 이유: V17__allow_negative_paid_commission_amount.sql 이 develop 에
--   먼저 병합돼 있고, V18·V19 는 아직 병합 전인 feature 브랜치(#100 계열)가 선점하고
--   있어 그 번호를 쓰면 병합 시점에 충돌한다. V6_1 과 같은 서브버전 명명 관례를 따랐다.
--   local 프로필은 db/migration 과 db/demo 를 한 버전 시퀀스로 합쳐 적용하므로
--   두 폴더에 걸쳐서도 버전 번호가 겹치면 안 된다.
--
--   화면정의서(:229)는 '준법·감사', 시드명세서(:256)는 '준법·감사 조회자'로
--   COMPLIANCE 표시명을 서로 다르게 규정하고 있었다(#86). '준법·감사'로 확정 —
--   messages.properties / messages_ko.properties 의 화면 배지 라벨과 일치하는
--   화면정의서 쪽 값이다. 시드명세서·운영정책서는 이 값으로 교정했다.
--
--   V3__seed_demo_data.sql:63 이 '준법·감사 조회자'를 INSERT 하지만, 이미 develop 에
--   병합된 versioned migration 이라 직접 고치면 기존 DB 의 checksum 이 깨진다
--   (AGENTS.md, V6_1 헤더 참고). V3 는 ON CONFLICT DO NOTHING 이라 시드 재실행으로도
--   안 바뀌므로 UPDATE 로 보정한다. 신규 DB 는 V3 → V17_1 순서로 적용돼 최종 상태가 같다.
-- ============================================================================

SET search_path TO fgc, public;

UPDATE app_role
   SET role_name = '준법·감사'
 WHERE role_code = 'COMPLIANCE'
   AND role_name = '준법·감사 조회자';
