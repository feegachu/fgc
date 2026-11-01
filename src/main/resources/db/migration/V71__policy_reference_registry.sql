-- #718 FUN-016 정책 근거 코드 레지스트리.
-- 정책 작성 시 regulationRefs/sourceRefs/sourceDetails.ref가 "승인된 REG/SRC"인지 이 표로 검사한다.
-- 이전에는 이미 승인된 정책에 쓰인 코드만 허용해서, 규제조문표에 있지만 아직 쓰이지 않은 코드를 처음 등록할 수 없었다.
-- REG는 docs/07_규제조문표_v0.2.1.md의 REG-01~24·REG-06A~C와 그 항목명이다.
-- SRC는 저장소 문서·시드에 인용된 코드다. SRC 제목·원문 경로의 정본(근거대장)은 저장소 밖(FGC-Vault)이라 제목을 비워 둔다.
-- 코드 추가는 규제조문표 개정과 함께 새 마이그레이션으로 한다.
SET search_path TO fgc, public;

CREATE TABLE policy_reference (
    ref_code   varchar(10)  PRIMARY KEY CHECK (ref_code ~ '^(REG-[0-9]{2}[A-Z]?|SRC-[0-9]{3})$'),
    ref_kind   varchar(3)   NOT NULL CHECK (ref_kind IN ('REG', 'SRC')),
    title      varchar(200),
    created_at timestamptz  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_policy_reference_kind CHECK (left(ref_code, 3) = ref_kind)
);

INSERT INTO policy_reference (ref_code, ref_kind, title) VALUES
    ('REG-01', 'REG', '판매수수료 구조'),
    ('REG-02', 'REG', '선지급수수료'),
    ('REG-03', 'REG', '유지관리수수료'),
    ('REG-04', 'REG', '4년 한시 특례'),
    ('REG-05', 'REG', '장기유지수수료'),
    ('REG-06', 'REG', '공통비·간접지원경비'),
    ('REG-06A', 'REG', '초년도 1,200%룰의 공통비'),
    ('REG-06B', 'REG', '유지관리수수료의 간접지원경비'),
    ('REG-06C', 'REG', '일반 공통비 안분정책'),
    ('REG-07', 'REG', '유지관리 수령인'),
    ('REG-08', 'REG', '초년도 1,200%룰'),
    ('REG-09', 'REG', 'GA 확대·수수료등 범위'),
    ('REG-10', 'REG', '준법경영비 3% 공제'),
    ('REG-11', 'REG', '한도 계산 제외항목'),
    ('REG-12', 'REG', '차익거래 방지'),
    ('REG-13', 'REG', '유지관리수수료의 미지급'),
    ('REG-14', 'REG', '회사별 환수율'),
    ('REG-15', 'REG', '구 분할지급제 삭제'),
    ('REG-16', 'REG', '정착지원금 환수'),
    ('REG-17', 'REG', '판매수수료 비교공시'),
    ('REG-18', 'REG', '대형 GA 비교설명'),
    ('REG-19', 'REG', '분급 적용례·룰셋 판정'),
    ('REG-20', 'REG', '경력설계사 정착지원금 귀속'),
    ('REG-21', 'REG', '신인모집종사자·신인활동지원비'),
    ('REG-22', 'REG', '지급기준 버전·우회지급'),
    ('REG-23', 'REG', '예상 해약환급률표'),
    ('REG-24', 'REG', '상품위원회'),
    ('SRC-001', 'SRC', NULL),
    ('SRC-002', 'SRC', NULL),
    ('SRC-003', 'SRC', NULL),
    ('SRC-004', 'SRC', NULL),
    ('SRC-005', 'SRC', NULL),
    ('SRC-006', 'SRC', NULL),
    ('SRC-008', 'SRC', NULL),
    ('SRC-009', 'SRC', NULL),
    ('SRC-010', 'SRC', NULL),
    ('SRC-013', 'SRC', NULL),
    ('SRC-014', 'SRC', NULL),
    ('SRC-016', 'SRC', NULL),
    ('SRC-017', 'SRC', NULL),
    ('SRC-018', 'SRC', NULL),
    ('SRC-019', 'SRC', NULL),
    ('SRC-020', 'SRC', NULL),
    ('SRC-021', 'SRC', NULL),
    ('SRC-022', 'SRC', NULL),
    ('SRC-023', 'SRC', NULL),
    ('SRC-024', 'SRC', NULL),
    ('SRC-025', 'SRC', NULL),
    ('SRC-026', 'SRC', NULL),
    ('SRC-027', 'SRC', NULL),
    ('SRC-028', 'SRC', NULL),
    ('SRC-029', 'SRC', NULL),
    ('SRC-031', 'SRC', NULL),
    ('SRC-032', 'SRC', NULL),
    ('SRC-033', 'SRC', NULL),
    ('SRC-034', 'SRC', NULL),
    ('SRC-035', 'SRC', NULL),
    ('SRC-036', 'SRC', NULL),
    ('SRC-037', 'SRC', NULL),
    ('SRC-043', 'SRC', NULL),
    ('SRC-045', 'SRC', NULL),
    ('SRC-049', 'SRC', NULL),
    ('SRC-050', 'SRC', NULL),
    ('SRC-051', 'SRC', NULL),
    ('SRC-052', 'SRC', NULL),
    ('SRC-054', 'SRC', NULL),
    ('SRC-055', 'SRC', NULL),
    ('SRC-056', 'SRC', NULL),
    ('SRC-057', 'SRC', NULL),
    ('SRC-058', 'SRC', NULL),
    ('SRC-059', 'SRC', NULL),
    ('SRC-060', 'SRC', NULL),
    ('SRC-061', 'SRC', NULL),
    ('SRC-062', 'SRC', NULL),
    ('SRC-064', 'SRC', NULL),
    ('SRC-065', 'SRC', NULL),
    ('SRC-066', 'SRC', NULL);

-- 정책버전별 근거 상태 태그(FUN-016 인수조건: REG/SRC 근거·상태 태그 조회).
-- 허용 값은 요구사항의 현행규정·개정고시·시행세칙·FAQ·회사정책이다. 기존 DRAFT 전용 트리거가 승인본 변경을 막는다.
ALTER TABLE policy_source_detail ADD COLUMN status_tags text[] NOT NULL DEFAULT '{}'
    CONSTRAINT ck_policy_source_detail_status_tags
    CHECK (status_tags <@ ARRAY['현행규정', '개정고시', '시행세칙', 'FAQ', '회사정책']::text[]);
COMMENT ON COLUMN policy_source_detail.status_tags IS 'FUN-016 근거 상태 태그. 현행규정·개정고시·시행세칙·FAQ·회사정책 중 복수 선택';

COMMENT ON TABLE policy_reference IS 'FUN-016 정책 근거 코드 레지스트리. REG 항목명은 규제조문표 기준, SRC 제목은 저장소 밖 근거대장이 정본이라 비워 둠';
