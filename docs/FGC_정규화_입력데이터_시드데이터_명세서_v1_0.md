---
title: "FGC 정규화 입력데이터·시드데이터 명세서"
version: "1.0"
status: "규제·스키마·추가자료 정합성 보정본 — 배포용"
effective_date: "2026-08-03"
revised_at: "2026-10-22"
database_baseline: "PostgreSQL 17 · Flyway db/migration V1~V55 + db/demo V3~V56 (develop fe6d0e6c, 2026-10-22)"
operating_policy: "FGC 가상 GA 운영정책서 v1.0"
regulation_baseline: "FGC 규제 근거·조문표 v0.2.1"
---

# FGC 정규화 입력데이터·시드데이터 명세서 v1.0

> 이 문서는 FGC가 원수사 데이터 수집·표준화를 하지 않는다는 전제에서,  
> **어떤 데이터가 어떤 형태로 DB에 미리 존재해야 하는지**,  
> **어떤 합성 시나리오로 계산결과를 검증해야 하는지**를 확정한다.

> **정합성 기준:** 규제 기대값은 `FGC 규제 근거·조문표 v0.2.1`의 REG-01~24를 따르고, 컬럼·제약·적재순서는 `src/main/resources/db/migration/`의 최신 적용본(V55)을 따른다. 스키마는 버전 숫자(v2.1.x)가 아니라 Flyway 버전과 커밋 SHA로 부른다. SRC 정의는 `docs/FGC-Vault/00_인덱스/근거대장.md`(활성 레지스트리 `99_근거자료.csv`)를 따른다. `db/demo`의 현재 데이터는 아래 전체 GOLDEN 목표의 축소 fixture다.

---

## 0. 핵심 원칙

1. 실제 고객·설계사 개인정보를 사용하지 않는다.
2. 모든 데이터는 합성값 또는 공개자료를 변형한 값이다.
3. 실제 보험회사명·실제 증권번호·실제 지급액을 그대로 재현하지 않는다.
4. 기준정보·정책·입력사실만 시드한다.
5. 스케줄·검증결과·원장·대사결과·예외는 애플리케이션이 생성한다.
6. 동일 시드를 반복 적용해도 결과가 같아야 한다. 승인 시각 등 시각 값도 고정 리터럴을 쓴다(기존 예외: V56의 `clock_timestamp()` — 적용된 파일이라 고칠 수 없다).
7. 기술키인 `bigint identity` 값을 SQL에 직접 하드코딩하지 않는다.
8. 참조는 `insurer_code`, `organization_code`, `agent_code`, `policy_code`, `contract_no` 같은 업무코드로 찾는다.
9. 금액은 `numeric(15,2)`를 사용한다. 수수료·환급·환수 요율은 `numeric(9,6)`, 1,200% 룰셋 파라미터는 `numeric(7,4)`, 계산 사용률은 `numeric(12,6)`을 사용한다.
10. Java에서는 `BigDecimal`만 사용하고 `double`, `float`를 금지한다.
11. 규제 수치·시행일·FAQ 상태는 Java·SQL 상수로 고정하지 않고 승인된 정책버전과 파라미터로 적재한다.
12. 규제상 `확정`, FGC의 `해석`, 회사의 `사적기준`, 고의 오류 시나리오를 데이터와 기대값에서 명시적으로 구분한다.

---

# 제1장 데이터 세트 구성

## 1. 데이터 프로파일

| 프로파일 | 목적 | 설계사 | 계약 | 예상 스케줄행 | 사용시점 |
|---|---|---:|---:|---:|---|
| `GOLDEN` | 기능·인수시험 (`db/demo`, 불변) | 현재 8명 / 목표 25명 | 현재 21건 / 목표 **68건** | 약 3,500행 | 개발·테스트·발표 |
| `DAR012` | 시연·성능·AI 평가 (2차, §50) | 600명 | 1,000 → 3,000 → 12,000건 | 최대 2,016,000행 | 발표 리허설·성능 측정 |

옛 `DEMO`(200명·500건)·`LOAD`(3,200명·50,000건) 프로파일은 2차 범위에서 `DAR012`로 대체한다(DAR-012). 운영정책서 제1조의 "소속 설계사 3,200명"과의 차이는 §6 참조.

`GOLDEN`은 사람이 계산기로 검산할 수 있는 **목표 데이터 세트**다. 시나리오 간 간섭을 막기 위해 68개 계약을 고정 배정한다(기존 64건 + §30 수취인 짝짓기 REC-11~14의 4건, SRC-064).
`DAR012`는 회사 규모를 표현하기 위한 자동생성 데이터이며, 대표 설계사 1명과 일부러 심은 이상 사례(정답표 보유)만 개별 스토리를 가진다.

### 현재 체크인된 구현 현황

전 demo 시드를 적용한 뒤의 건수다(기본 수량은 develop `fe6d0e6c` SQL 정적 계산, 역할·사용자는 #634 V64 반영).

| 대상 | 건수 | 출처 |
|---|---:|---|
| 역할 / 사용자 | 5 / 5 | V3, V17_1(COMPLIANCE 표시명), V64(AGENT·agent01, 초기 DISABLED) |
| 조직 | 9 | V3 |
| 보험회사 | 6 (FGN02는 상품 없음) | V3 |
| 설계사 | 8 (강동2팀 팀장 `A-TL-002` 포함) | V3, V34 |
| 원수사 설계사코드 | 10 | V3, V4 |
| 상품 / 판매버전 | 5 / 7 | V3, V4, V49 |
| 수수료 항목 | 11 | V3 |
| 정책버전 행 / ACTIVE 정책코드 | 22 / 12 | V2, V3, V6_1, V42, V45_1, V46, V52, V56 |
| 수수료 규칙 | 8 (원수사→GA 2, GA→FC 6) | V3 |
| ACTIVE 1,200% 룰셋 / 항목분류 | 4 / 36 | V3, V6_1 → V46·V52가 v3로 복제 |
| 예상 해약환급률표 / 행 | 5 / 180 | V3, V42 |
| 계약 | 21 | V3 6 + V4 14 − V49 1(G3) + V45_1 2 (`TmChannelRemovalMigrationIntegrationTest`) |
| 계약상태 사건 | 28 | V3, V4, V45_1 |
| 재무 스냅샷 | 23 | V3, V4, V45_1 |
| 지급명세 묶음 | 15 | V3 3(2026-07), V4 12 |
| 지급 건 / 귀속행 | 81 / 82 (DRAFT 2건: `GA-2026-07-0303`, `GA-2026-07-0001`) | V3, V4, V45_1 |
| 결과 테이블 | 0 (스케줄·검증·원장·대사·예외는 앱이 생성) | — |

| 구분 | 현재 상태 |
|---|---|
| 데모 시드 파일 | V3(GOLDEN 축소본), V4(검증 시나리오 14건), V6_1(원수사→GA 룰셋 보정), V8(환산코드 변경), V17_1(COMPLIANCE 표시명), V34(A-TL-002), V42(STD-SAV-A 환급률표), V45_1(시행 후 차익거래 계약 2건 `FGC-FGL02-202603-0475`·`FGC-FGL01-202603-0475`, REG-12·REG-19), V46(CAP 정책 v2 — CLAWBACK 사유 교정 #478), V49(TM 판매버전·G3 삭제, SRC-036), V52(CAP 정책 v3 — 정착지원금 모집월 #525), V56(ARB-APPLICABILITY-2026 v2 — 환급금 합산 기간 #480), V65(4년·7년 분급 규제 상한 룰셋 `REG-FOUR-YEAR-2027-V1`·`REG-SEVEN-YEAR-2029-V1` #538) |
| 시나리오 식별 | V3의 C001~C006 별칭과 V4의 R1~R8·A1~A3·G1·G2(G3은 V49로 삭제). 아래 목표 구간 T001~T068과 다르다(§24). 현행 정답은 §31-1 |
| 현재 수수료 규칙 | 보험회사→GA 900%@1·8%@2~12, GA→FC FC 650%@1·관리자 40/30/20%@1·시책 100%@1·유지 5%@2~12 (`INS-CUR-2026-V1`·`GA-CUR-2026-V1`). 운영정책서 제19·20조 패턴(1,485%·740%)의 축소판 |
| 정착지원금 | 현재 210,000원(2건에 105,000원씩). 아래 2,400,000원/4,000,000원은 전체 GOLDEN 목표 |
| 결과 시드 | 스케줄·검증·원장·대사 결과는 넣지 않음 |

따라서 현재 `db/demo`만 실행한 결과를 “25명·68계약 GOLDEN 완료”로 표시하지 않는다. 이 문서의 T001~T068(§24)는 전체 GOLDEN 구현을 위한 목표 명세이며, 해당 SQL과 기대값 파일이 추가되어야 완료된다.


**목표만 있고 아직 없는 것**: `golden_expected_assertions.sql`·`load_data_generator.sql`(`src/test/resources/sql` 디렉터리 없음), T001~T068, STD-AUTO-A(FGN02 상품 0), STD-TERM-A `2029-SEVEN-YEAR`, REVIVED 사건 0건, 차익거래 1~36차월 월별 스냅샷(A1 등은 1행씩), REC-09·10 fixture, `committee_document_ref`(시드 NULL), `agent_history` 행(§8), 4년·7년 분급 GA 지급률 정책(`commission_rule`·계약체결비용 `policy_parameter`, #649·#651; 규제 상한 룰셋은 V65로 적재), AGENT 계정(§5), DAR-012 생성기(§50), AI 입력 샘플(§52), 협회 등급자료(§53). 각 항목은 해당 SQL·기대값이 들어올 때 이 목록에서 지운다.

## 2. 시드 파일 구성

현재 저장소의 실제 경로와 Flyway 버전 체인은 다음과 같다.

```text
src/main/resources/db/migration/   V1 ~ V61 (V47·V53 결번). 스키마·제약·트리거 + 운영 기준정보 일부
  V2   batch_watermark 초기 2행 · STATUS-MONTH-RULE-2026 v1(유산, §4-1)
  V16  journal_account 8개 계정과목
src/main/resources/db/demo/        Flyway 버전 순서(두 폴더가 한 시퀀스)
  V3     기준정보·정책·계약 6·지급·귀속 (GOLDEN 축소본)
  V4     검증 시나리오 계약 14(R1~R8·A1~A3·G1~G3)
  V6_1   원수사→GA 룰셋 보정 REG-CAP-INS-2021-V2(0%)·2027-V1(3%)
  V8     premium_conversion_rule_code 신규 코드로 변경
  V17_1  COMPLIANCE 표시명 '준법·감사'
  V34    강동2팀 팀장 A-TL-002
  V42    STD-SAV-A 예상 해약환급률표(INS-REFUND-2026-SAV-V1)
  V45_1  ARB-APPLICABILITY-2026 v1 + 시행 후 차익거래 계약 2
  V46    현행 CAP 정책 v2 — CLAWBACK 사유 교정(#478)
  V49    TM 판매버전·G3 삭제(SRC-036)
  V52    현행 CAP 정책 v3 — 정착지원금 모집월 사유 교정(#525)
  V56    ARB-APPLICABILITY-2026 v2 — 환급금 합산 기간 파라미터(#480)
  V65    4년·7년 분급 규제 상한 룰셋(REGULATORY, 한도 행 포함, #538
  V60    STD-LIFE-B 2026-CURRENT-B 판매기간 종료(#540)
  V64    AGENT 역할·agent01 → A-FC-001(초기 비밀번호 미지정·DISABLED, #634)

src/test/resources/sql/            목표 파일(golden_expected_assertions.sql 등) — 아직 없음
```

### 원칙

- 운영과 같은 기준정보·정책은 versioned migration(`V__`)으로 넣는다.
- 초기화는 DB 안에서 행을 지우지 않고 DB를 새로 만든다(§43).
- `R__` 반복 마이그레이션을 쓰지 않는다.
- 데모 시드(`db/demo`)는 별도 프로필이 아니라 **Flyway 위치**로 켠다(`demo` 프로필은 없다). 현재 `db/demo`를 적재하는 곳은 다음과 같다(develop `fe6d0e6c`).

| 환경 | 설정 위치 | db/demo |
|---|---|---|
| 로컬 | `application-local.yml` (저장소 밖, `.gitignore:58`) | 팀 관례상 포함 — 각자 확인 |
| 통합 테스트 | `src/test/resources/application-test.yml` | 포함 |
| E2E | `frontend/scripts/e2e-server.mjs` | 포함 |
| AWS 개발·검증계 | `podman/fgc-app-combined.yaml:19`, `podman/fgc-app-was.yaml:19` (`SPRING_FLYWAY_LOCATIONS`) | 포함 |
| 신한 prd(발표 시연) | `infra/shinhan/compose.yaml:39` | 포함 — 이미 적용된 데모 이력 보존 목적(:37-38) |
| 실제 운영 | 없음 | 해당 없음. 실제 운영 DB가 생기면 `db/demo`를 빼고 기준정보 이관 절차를 따로 만든다 |

- 따라서 **`db/demo`에 넣는 모든 행은 신한 prd에도 들어간다.** 시연 환경 밖으로 내보내면 안 되는 데이터나 대량 데이터(DAR-012)는 `db/demo`에 넣지 않는다(§50).
- **Flyway 번호**: `db/migration`과 `db/demo`는 하나의 버전 시퀀스다. #634는 사용자 지정 **V64**를 사용한다. 이 브랜치의 다음 번호는 **V65**이며 병합 직전 다른 PR의 번호도 대조한다. 결번(V47·V53 등)은 채우지 않는다. 같은 번호를 두 폴더에 쓰지 않는다. 이미 적용된 파일은 고치지 않고 새 번호로 보정한다(SRC-027 D-04).

---

# 제2장 적재 계층과 순서

## 3. 데이터 계층

| 계층 | 설명 | 대표 테이블 |
|---|---|---|
| `REFERENCE` | 코드·조직·보험회사·상품 | insurer, organization, agent, product |
| `POLICY` | 불변 계산규칙 | policy_version, commission_rule, cap_rule_set |
| `INPUT_FACT` | 외부에서 이미 정규화된 계약·실제 돈 | insurance_contract, statement_batch, commission_transaction |
| `GENERATED` | FGC가 계산해 만드는 결과 | schedule_*, cap_check, arbitrage_check, journal_*, reconciliation_* |
| `WORKFLOW` | 예외·감사 | exception_*, audit_log |
| `PHASE2` | 환수·유지관리 확장 | clawback_*, recovery_transaction, maintenance_check |

## 4. FK 기준 적재순서

```text
1. app_role
2. organization
3. app_user
4. insurer
5. agent
6. agent_insurer_code
7. product
8. product_offering
9. commission_item
10. policy_version
11. policy_parameter
12. commission_rule
13. allocation_policy
14. cap_rule_set
15. cap_rule_item
16. refund_rate_table
17. refund_rate_line
18. policy_version 승격 (DRAFT → APPROVED → ACTIVE)
19. insurance_contract
20. contract_status_event
21. contract_status_event_processing (처리 fixture가 필요할 때만)
22. contract_financial_snapshot
23. statement_batch
24. commission_transaction(DRAFT)
25. transaction_attribution
26. commission_transaction CONFIRMED 전환
```

### 4-1. 정책 시드는 반드시 DRAFT로 넣고 나중에 승격한다

**이 순서를 지키지 않으면 정책 시드가 통째로 실패한다.** PostgreSQL 실행으로 확인된 사항이다.

스키마 v2.1.1부터 정책 잠금이 강해졌다. 다음 두 가지가 금지된다.

1. `APPROVED`·`ACTIVE`·`RETIRED` 정책 아래에는 자식 행을 **새로 넣을 수도 없다.**
   대상 테이블: `commission_rule`, `cap_rule_set`, `cap_rule_item`,
   `allocation_policy`, `refund_rate_table`, `refund_rate_line`, `policy_parameter`
2. `DRAFT → ACTIVE` 직행 전이가 막혀 있다.
   허용 전이는 `DRAFT → REVIEW → APPROVED → ACTIVE → RETIRED`이며,
   `DRAFT → APPROVED`, `REVIEW → DRAFT`, `APPROVED → RETIRED`도 허용된다(V6).

따라서 시드 SQL은 다음 형태여야 한다.

```sql
-- 10. 정책버전을 DRAFT로 INSERT (DRAFT가 아니면 INSERT 자체가 실패한다, V6)
--     아래 'GA-CUR-LIFE-A-2026-V1'은 새 정책을 만드는 예시다. 현재 시드의 GA 현행 정책 코드는 'GA-CUR-2026-V1'이다(§13).
INSERT INTO fgc.policy_version
  (policy_code, policy_name, policy_type, source_class,
   version_no, effective_from, status, regulation_refs, source_refs, created_by)
SELECT
  'GA-CUR-LIFE-A-2026-V1', 'GA 현행 지급정책 A', 'CURRENT_COMMISSION',
  'GA_POLICY', 1, DATE '2026-01-01', 'DRAFT',
  ARRAY['REG-09','REG-22'], ARRAY['FGC 가상 GA 운영정책서 v1.0'], u.user_id
FROM fgc.app_user u
WHERE u.login_id = 'settle01';

-- 11~16. 자식 행 적재 (정책이 DRAFT라서 통과한다)
INSERT INTO fgc.commission_rule (...) VALUES (...);
INSERT INTO fgc.cap_rule_set     (...) VALUES (...);
INSERT INTO fgc.policy_parameter (...) VALUES (...);
INSERT INTO fgc.refund_rate_table(...) VALUES (...);
INSERT INTO fgc.refund_rate_line (...) VALUES (...);

-- 16-1. 승격: 반드시 두 번의 UPDATE
--       approved_at은 APPROVED로 가는 UPDATE에서 함께 넣어야 한다.
--       (APPROVED 이후에는 approved_at도 수정 불가)
UPDATE fgc.policy_version
   SET status = 'APPROVED',
       approved_at = TIMESTAMPTZ '2026-01-01 09:00:00+09', -- 고정 합성 승인 시각(§0, SRC-065)
       approved_by = (SELECT user_id FROM fgc.app_user WHERE login_id = 'gaadmin'),
       approval_evidence_ref = 'SYNTHETIC:POLICY-APPROVAL-2026-001'
 WHERE policy_code = 'GA-CUR-LIFE-A-2026-V1' AND version_no = 1;

UPDATE fgc.policy_version
   SET status = 'ACTIVE'
 WHERE policy_code = 'GA-CUR-LIFE-A-2026-V1' AND version_no = 1;
```

#### 자주 만나게 될 오류 메시지

| 오류 메시지 | 원인 | 해결 |
|---|---|---|
| `policy_version must be inserted as DRAFT (got …)` | 정책을 DRAFT 아닌 상태로 INSERT | DRAFT로 INSERT한 뒤 두 번의 UPDATE로 승격 (V6) |
| `Rules under locked policy version N are immutable` | APPROVED·ACTIVE·RETIRED 정책에 자식 행을 넣거나 고치거나 지움 | 새 `version_no`를 DRAFT로 만들어 그 아래에 넣는다 (V2_1 guard_policy_child_mutation) |
| `Invalid policy status transition: DRAFT -> ACTIVE` | 한 번의 UPDATE로 활성화하려 함 | `DRAFT → APPROVED → ACTIVE` 두 번으로 나눔 |
| `APPROVED/ACTIVE/RETIRED policy version is immutable` | 승인 뒤 `approved_at`·`approved_by`·유효기간 등을 고침 | 승인 UPDATE에서 한 번에 넣는다. 고치려면 새 버전 (V6) |
| `Only DRAFT policy versions may be deleted` | 승인된 정책 삭제 | 삭제하지 않고 RETIRED (V6) |
| `ck_policy_approval` 위반 | `APPROVED` 이상인데 `approved_at`이 비어 있음 | `APPROVED`로 가는 UPDATE에서 `approved_at`을 함께 설정 |
| `ck_policy_authorship` 위반 | 작성자·승인자가 없거나 동일인임 | DRAFT INSERT에 `created_by`, 승인 UPDATE에 다른 `approved_by`를 저장 |

#### 정책 수정이 필요할 때

`ACTIVE` 정책의 요율을 고치는 마이그레이션은 작성하지 않는다.
새 `version_no`의 정책버전을 `DRAFT`로 만들고 자식 행을 넣은 뒤 **APPROVED → (이전 버전 RETIRED) → ACTIVE** 순서로 바꾼다. 같은 `policy_code`에 ACTIVE가 둘이 되는 순간을 만들지 않는다(DB에 "코드당 ACTIVE 1개" 제약이 없다 — V46·V52·V56 선례, `CapGrossPolicyMigrationIntegrationTest`). 과거 계산 결과는 계속 이전 버전을 참조한다.

`V2`가 직접 `ACTIVE`로 삽입한 `STATUS-MONTH-RULE-2026` v1은 유산 예외다. `db/demo/V3`가 적용되면 이를 폐기하고 작성자·승인자가 분리된 v2를 발행하지만, 기본 migration만 적용한 DB에는 v1이 남을 수 있다. 이 유산 행 때문에 v2.1.6의 저작자 CHECK는 `NOT VALID`다. 신규 시드가 이 예외를 모방해서는 안 되며, 운영 전 유산 행 폐기·정상 재발행·제약 VALIDATE를 별도 마이그레이션으로 수행한다. 2026-10-22 기준 VALIDATE는 아직 실행하지 않았다. 이 설명은 `db/demo`를 적용한 환경에서만 v1이 RETIRED가 된다는 뜻이다. 실제 운영 DB에는 `db/demo`를 적용하지 않으며, 유산 행 폐기·작성자/승인자 분리 정상 재발행·기존 행 정합성 점검·제약 `VALIDATE`를 별도 마이그레이션으로 완료해야 한다(SRC-027 D-04·SRC-049·SRC-065).

다음 테이블은 초기 입력시 비워둔다.

```text
schedule_header
schedule_line
validation_run
validation_target
cap_check
cap_check_detail
arbitrage_check
contract_status_event_processing
acquisition_cost_check
acquisition_cost_check_detail
maintenance_check
journal_header
journal_line
reconciliation_run
reconciliation_result
reconciliation_match
exception_case
exception_occurrence
exception_action
journal_correction_group
payment_journal_correction
auth_refresh_token
```

`batch_watermark`는 V2가 만들고 V3가 시드 직후 시각으로 맞춘다 — 시드 데이터가 첫 일일 배치에 다시 읽히지 않게 하기 위해서다. `journal_account`는 V16이 8개 계정과목을 넣는다(운영 기준정보).

이 테이블에 결과를 미리 넣으면 애플리케이션이 실제로 계산했는지 증명할 수 없다.

---

# 제3장 기준정보 시드

## 5. 역할

| role_code | 이름 |
|---|---|
| `SYSTEM_ADMIN` | 시스템관리자 |
| `GA_ADMIN` | GA관리자 |
| `SETTLEMENT` | 정산담당자 |
| `COMPLIANCE` | 준법·감사 |
| `AGENT` | 설계사 (2차, FUN-085 — #634 V64 추가) |

### GOLDEN 사용자

| login_id | 역할 | 비고 |
|---|---|---|
| `admin` | SYSTEM_ADMIN | 전체관리 |
| `gaadmin` | GA_ADMIN | 정책·조직 조회 |
| `settle01` | SETTLEMENT | 정산 실행·예외처리 |
| `audit01` | COMPLIANCE | 조회 전용 |
| `agent01` | AGENT | (2차, V64) `app_user.agent_id` = `A-FC-001`. 초기 DISABLED, 개별 비밀번호 설정 후 활성화 |

비밀번호는 BCrypt 해시로 저장한다. 문서에 평문 비밀번호를 남기지 않는다.

`agent01`은 공통 시연 자격 증명을 복제하지 않는다. V64 신규 행의 `password_hash='!'`는 로그인 불가능한 미지정 표식이고 `account_status='DISABLED'`다. 시연 관리자가 별도 Secret으로 전달한 비밀번호를 애플리케이션의 BCryptPasswordEncoder로 해시한 뒤, 보호된 관리 연결에서 해당 행에 해시와 `ACTIVE` 상태를 함께 설정해야 실제 로그인할 수 있다. SQL·PR·로그에 비밀번호나 해시를 붙이지 않는다. 통합 테스트는 트랜잭션 안에서 일회성 UUID 비밀번호를 설정·활성화한 뒤 롤백한다. 이미 존재하는 동일 연결의 agent01은 V64가 덮어쓰지 않으므로 배포 전 별도 보호 검증이 필요하다.

- **AGENT 역할·`agent01`(2차)**: `db/demo/V64__seed_agent_role_and_account.sql`로 추가한다(#634). 스키마는 준비돼 있다(`app_user.agent_id`, `app_role`에 CHECK 없음). test·E2E에서 쓰려면 GOLDEN 설계사에 연결해야 하므로 기본 연결은 `A-FC-001`(정상 C001, 실효 C005, 원수사코드 3사)로 한다 — 프로젝트 가정이며 #634의 V64에서 기본 연결로 적용한다. DAR-012 생성기는 대표 설계사(정상·실효·부활·금액 미확인·환수)를 데이터셋 안에 만들고 그 DB에서만 `agent01.agent_id`를 그 설계사로 바꾼다(대표 계정은 계속 1개). (FUN-085, DAR-012)
- **신한 prd 배포 전 필수 — 기존 데모 계정 보호**: V3 주석에 알려진 공통 비밀번호가 남아 있고 적용된 V3는 고치지 않는다(SRC-027 D-04). 현재 신한 prd 구성도 `db/demo`를 읽으므로(§2), 외부 트래픽을 열기 전에 배포 단계에서 `admin`·`gaadmin`·`settle01`·`audit01`·`agent01` 각각의 비밀번호를 안전하게 변경하거나 계정을 잠그고 결과를 검증한다. 새 비밀번호는 Secret으로 전달해 BCrypt 해시만 저장하며, 기존 공통 자격 증명으로 다섯 계정 모두 로그인할 수 없음을 확인한다. 잠금은 계정 상태와 인증 거절을 함께 확인한다. 없는 계정의 401 응답만으로는 합격 처리하지 않는다(SRC-065).
- **보호·검증을 보장할 수 없으면 배포 중단**: 신한 prd에서 `db/demo`를 제외하고 별도 기준정보·정책·보호된 계정 이관 절차를 마련한다. 이미 데모가 적재된 DB는 locations 변경만으로 계정이 없어지지 않으므로 기존 계정과 agent01의 보호 검증도 필요하다. 새 시드·문서·PR·배포 로그에는 평문 비밀번호를 남기지 않는다. 현 배포 스크립트에 이 보호 단계가 구현됐다는 뜻은 아니며 S44의 배포 검증에서 증명해야 한다(SRC-065).

## 6. 조직

### 회사 전체 조직 규모

- 2차 시연 기준(DAR-012): GA 1 · 지점 12(예시) · 설계사 600(활동 ≥ 500). 데이터셋 메타데이터의 "기준일 직전 분기 일평균 소속 설계사 600명"은 대형 GA 판정용 프로젝트 가정이다(OI-024, SRC-022 제4-11조①).
- 운영정책서 제1조의 옛 규모(본부 4·지사 20·팀 100, 설계사 3,200명)는 2차에서 쓰지 않는다.
- GOLDEN 조직(아래 9개)은 그대로 둔다.

### GOLDEN 조직

| organization_code | 이름 | 유형 | 상위 |
|---|---|---|---|
| `FGC-GA` | FGC금융서비스 | GA | 없음 |
| `FGC-HQ` | FGC 본사 | HQ | FGC-GA |
| `FGC-D01` | 동부본부 | DIVISION | FGC-HQ |
| `FGC-B0101` | 강동지사 | BRANCH | FGC-D01 |
| `FGC-T010101` | 강동1팀 | TEAM | FGC-B0101 |
| `FGC-T010102` | 강동2팀 | TEAM | FGC-B0101 |
| `FGC-D02` | 서부본부 | DIVISION | FGC-HQ |
| `FGC-B0201` | 마포지사 | BRANCH | FGC-D02 |
| `FGC-T020101` | 마포1팀 | TEAM | FGC-B0201 |

유효기간은 2026-01-01부터 시작하고 종료일은 `NULL`로 둔다.

## 7. 보험회사

실제 보험회사명 대신 합성 명칭을 사용한다.

| insurer_code | 이름 | 유형 |
|---|---|---|
| `FGL01` | 미래가상생명 | LIFE |
| `FGL02` | 한빛가상생명 | LIFE |
| `FGL03` | 새봄가상생명 | LIFE |
| `FGL04` | 온누리가상생명 | LIFE |
| `FGN01` | 안전가상손해보험 | NON_LIFE |
| `FGN02` | 믿음가상손해보험 | NON_LIFE |

## 8. 설계사

GOLDEN은 다음 직급을 포함한다.

| agent_code | 직급 | 상태 | 용도 |
|---|---|---|---|
| `A-FC-001` | FC | ACTIVE | 정상 설계사 |
| `A-FC-002` | FC | ACTIVE | 경력 정착지원금 |
| `A-FC-003` | FC | ACTIVE | 신인활동지원비 |
| `A-FC-004` | FC | TERMINATED | 설계사 불일치·해촉 사례 |
| `A-TL-001` | TEAM_LEADER | ACTIVE | 팀장수수료 |
| `A-TL-002` | TEAM_LEADER | ACTIVE | 강동2팀 팀장 — 팀마다 팀장이 있어야 관리자수수료 수취인이 정해진다(V34) |
| `A-BM-001` | BRANCH_MANAGER | ACTIVE | 지사장수수료 |
| `A-DH-001` | DIVISION_HEAD | ACTIVE | 본부장수수료 |

### 신인 판정 데이터

신인 여부는 등록 이력과 모집 이력을 하나의 불리언으로 먼저 합치지 않고, 등록예정일 직전 3년의 이력을 순서대로 조회한 뒤 판정한다. 등록 이력이 있더라도 같은 기간 모집 이력이 없으면 신인일 수 있고, 모집 이력이 있으면 신인이 아니다. (`REG-21`, `SRC-022`, `SRC-025`)

아래 `*_history_count`는 시나리오 판독용 논리 스냅샷이며 실제 이력 원천은 `agent_history`의 개별 행으로 저장한다. 물리 컬럼을 추가하는 의미가 아니다. (`REG-21`, `SRC-025`)

`A-FC-003` — 등록 이력은 있으나 모집 이력이 없어 신인으로 판정되는 정상 사례:

```text
latest_registration_date = 2026-07-01
prior_three_year_registration_history_count = 1
prior_three_year_recruitment_history_count = 0
prior_three_year_experience_yn = false  # 모집 이력 기준의 파생값
experience_checked_on = 2026-06-30
newcomer_support_eligible_yn = true
newcomer_support_end_date = 2027-06-30
evidence_ref = SYNTHETIC:REG-21:A-FC-003 registration_scheduled_date
history_window_start_date = registration_scheduled_date - 3 years
history_window_end_date = registration_scheduled_date - 1 day
newcomer_support_end_date = registration_scheduled_date + 1 year - 1 day
```

`agent_history`에는 위 등록·모집 이력의 발생일·종료일·확인일·출처참조를 각각 저장한다. 이력 원천이 없거나 두 이력을 구분할 수 없으면 `newcomer_support_eligible_yn`을 확정하지 않고 `REVIEW_REQUIRED`로 둔다. (`REG-21`, `SRC-025`)

> 현재 상태: `agent_history`는 시드가 없다(0행). 스키마의 `change_type`은 `RANK·ORG·STATUS·APPOINT·TERMINATE·EXPERIENCE`뿐이라 등록 이력과 모집 이력을 칸으로 구분할 수 없고 출처참조 칸도 없다. 적재할 때는 `change_type='EXPERIENCE'` 한 행에 `newcomer_snapshot = {registration_history:[…], recruitment_history:[…], window_start, window_end}`로 넣는다. 전용 칸이 필요한지는 근거 없음 — 확인 필요. 현재 데모는 `agent`의 요약 칸(`latest_registration_date`, `prior_three_year_experience_yn`, `experience_checked_on`)만 쓴다.

### 경력 정착지원 대상

`A-FC-002` — 최근 3년 내 모집 이력이 있어 신인이 아닌 사례:

```text
prior_three_year_registration_history_count = 1
prior_three_year_recruitment_history_count = 1
prior_three_year_experience_yn = true
newcomer_support_eligible_yn = false
newcomer_support_end_date = NULL   # 신인 아님
evidence_ref = SYNTHETIC:REG-21:A-FC-002 registration_scheduled_date
history_window_start_date = registration_scheduled_date - 3 years
history_window_end_date = registration_scheduled_date - 1 day
```

## 9. 원수사 설계사코드

각 보험회사마다 다른 코드를 만든다.

현재 시드(V3·V4)의 10건:

| insurer_code | insurer_agent_code | agent_code | effective_from | source_ref |
|---|---|---|---|---|

| insurer_code | insurer_agent_code | agent_code |
|---|---|---|
| FGL01 | `L01-77881` | A-FC-001 |
| FGL02 | `L02-11027` | A-FC-001 |
| FGN01 | `N01-99315` | A-FC-001 |
| FGL01 | `L01-77882` | A-FC-002 |
| FGL02 | `L02-11028` | A-FC-002 |
| FGL01 | `L01-77883` | A-FC-003 |
| FGL03 | `L03-55011` | A-FC-001 |
| FGL03 | `L03-55012` | A-FC-002 |
| FGL04 | `L04-33021` | A-FC-001 |
| FGL02 | `L02-11029` | A-FC-004 (해촉자, R5) |

`effective_from`은 2026-01-01이다. `source_ref`는 기존 행에서 NULL이며 신규 시드부터 `SYNTHETIC:AGENT-CODE-xxx`로 채운다.

AGENT_MISMATCH 시나리오는 실제명세에 존재하지 않거나 다른 설계사로 매핑되는 코드를 넣는다. R5 원수사 명세의 `L02-99999`는 일부러 매핑하지 않은 코드다(V4).

---

# 제4장 상품·판매버전 시드

## 10. 상품

| standard_product_code | insurer_product_code | 원수사 | 상품명 | 상품군 | 유형 |
|---|---|---|---|---|---|
| `STD-LIFE-A` | `P-A-001` | FGL01 | 가상 건강보장보험 A | HEALTH_PROTECTION | PROTECTION |
| `STD-LIFE-B` | `P-B-001` | FGL02 | 가상 저해지 건강보험 B | HEALTH_PROTECTION | PROTECTION |
| `STD-TERM-A` | `P-T-001` | FGL03 | 가상 경영인정기보험 | TERM_PROTECTION | PROTECTION |
| `STD-NL-A` | `P-N-001` | FGN01 | 가상 장기상해보험 A | LONG_TERM_NONLIFE | PROTECTION |
| `STD-SAV-A` | `P-S-001` | FGL04 | 가상 연금저축보험 | SAVINGS | SAVINGS |
| `STD-AUTO-A` | (미적재) | FGN02 | 가상 자동차보험 | AUTO | AUTO |

1차 규제 데모는 앞의 네 보장성 상품을 사용한다.

## 11. 상품 판매버전

| product | offering_version | sales_start | basic_document_version | basic_document_date | channel | fee_regime | 80% 공제 | 상품위원회 메타데이터 |
|---|---|---|---|---|---|---|---|---|
| STD-LIFE-A | `2026-CURRENT-A` | 2026-01-01 | BD-2026-01 | 2026-01-01 | FACE_TO_FACE | CURRENT | false | 승인일·문서참조·상태 |
| STD-LIFE-B | `2026-H1-B` | 2026-01-01 (~2026-06-30) | BD-2026-01 | 2026-01-01 | FACE_TO_FACE | CURRENT | true | 승인일·문서참조·상태 |
| STD-LIFE-B | `2026-CURRENT-B` | 2026-07-01 | BD-2026-07 | 2026-07-01 | FACE_TO_FACE | CURRENT | true | 승인일·문서참조·상태 |
| STD-LIFE-B | `2027-FOUR-YEAR` | 2027-01-01 | BD-2027-01 | 2027-01-01 | FACE_TO_FACE | FOUR_YEAR_2027 | true | 승인일·문서참조·상태 |
| STD-TERM-A | `2026-CURRENT-A` | 2026-01-01 | BD-2026-01 | 2026-01-01 | FACE_TO_FACE | CURRENT | true | 승인일·문서참조·상태 |
| STD-NL-A | `2026-CURRENT-A` | 2026-01-01 | BD-2026-01 | 2026-01-01 | FACE_TO_FACE | CURRENT | false | 승인일·문서참조·상태 |
| STD-SAV-A | `2026-CURRENT-A` | 2026-01-01 | BD-2026-01 | 2026-01-01 | FACE_TO_FACE | CURRENT | false | 승인일·문서참조·상태 (V4) |
| STD-TERM-A | `2029-SEVEN-YEAR` | 2029-01-01 | BD-2029-01 | 2029-01-01 | FACE_TO_FACE | SEVEN_YEAR_2029 | true | (목표 — 미적재) |

현재 판매버전은 7개다(V3 5개 + V4 `2027-FOUR-YEAR`·STD-SAV-A, V49가 TM 판매버전 삭제). `2029-SEVEN-YEAR`만 목표다.

`product_offering`은 REG-19 판정의 핵심이므로 `product_id`, `basic_document_date`를 생략한 실행용 시드를 허용하지 않는다. REG-24 관련 `committee_approval_date`, `committee_document_ref`, `governance_status`는 상품위원회 자체를 구현하는 값이 아니라 외부 승인결과를 연결하는 메타데이터다.

경계시험용으로 다음도 만든다.

- G1(적재됨): 계약일 2027-03-02, 판매버전 `2026-CURRENT-A`(CURRENT) → **분급 미적용(현행 체계)**. 계약연도만 보고 4년 분급으로 정하면 오답이다. 2026-10-22에 `REVIEW_REQUIRED`에서 정정했다(SRC-022 `:5096`·SRC-025 Q4, 운영정책서 제45조). V4 파일의 "전부 REVIEW_REQUIRED" 주석은 고치지 않으므로(SRC-027 D-04) 이 문서가 기준이다.
- G2(적재됨): 계약일 2026-11-10이 판매개시일 2027-01-01보다 이른 `2027-FOUR-YEAR` → `REVIEW_REQUIRED`·`POLICY_MISSING` (데이터를 고치지 않는다, V4)
- 목표(미적재): 계약일 2029년·`FOUR_YEAR_2027` 판매버전 (FR6은 테스트 픽스처로 확인한다)

자동으로 새 체계로 확정하지 않고 `REVIEW_REQUIRED` 기대값을 사용한다.

**경계 샘플 FR1~FR12 (테스트 픽스처 — `db/demo`에 넣지 않음, #538)**: `FeeRegimeBoundarySampleIntegrationTest`가 G1 계약과 판매버전 값을 테스트 안에서만 바꿔 만든다(롤백). 판정표(운영정책서 제45조)와 1:1이다.

| ID | 계약일 | 판매개시일 | 기초서류 버전·작성/변경일 | 선언 체계 | 기대 결과 |
|---|---|---|---|---|---|
| FR1 | 2027-03-02 | 2027-01-01 | BD-2027-01 · 2026-12-01 | FOUR | 4년 룰셋 `SELECTED` |
| FR2 (=G1) | 2027-03-02 | 2026-01-01 | BD-2026-01 · 2026-01-01 | CURRENT | `NOT_APPLICABLE` |
| FR3 | 2027-03-02 | 2027-01-01 | BD-2026-02 · 2026-02-01 | FOUR | `REVIEW_REQUIRED` (`REGIME_NOT_ELIGIBLE`) |
| FR4 | 2027-03-02 | 2026-12-31 | BD-2026-12 · 2026-12-01 | FOUR | `REVIEW_REQUIRED` (`REGIME_NOT_ELIGIBLE`) |
| FR5 (=G2 유형) | 2026-11-10 | 2027-01-01 | BD-2027-01 · 2027-01-01 | FOUR | `REVIEW_REQUIRED` (`CONTRACT_OUTSIDE_SALES_PERIOD`) |
| FR6 | 2029-03-02 | 2027-01-01 | BD-2027-01 · 2026-12-01 | FOUR | `REVIEW_REQUIRED` (`FOUR_YEAR_AFTER_SPECIAL_PERIOD`, 특례 종료 기준 미확정) |
| FR7 | 2029-03-02 | 2029-01-01 | BD-2029-01 · 2028-12-01 | SEVEN | 7년 룰셋 `SELECTED` (84회·장기유지 61~84차월) |
| FR8 | 2028-03-02 | 2028-01-01 | BD-2028-01 · 2027-12-01 | SEVEN | `REVIEW_REQUIRED` (`SEVEN_YEAR_IN_SPECIAL_PERIOD`) |
| FR9 | 2027-03-02 | 2027-01-01 | BD-2027-01 · 2026-03-01 | FOUR | 4년 룰셋 `SELECTED` (시행일 당일 작성 포함) |
| FR10 | FR1과 같음 | FR1과 같음 | FR1과 같음 | FOUR | FR1과 같은 결과 (판매버전 `channel_code`만 다름, SRC-036) |
| FR11 | 2027-03-02 | 2027-01-01 | BD-2027-01 · 2026-12-01 | CURRENT | `REVIEW_REQUIRED` (`CURRENT_BUT_ELIGIBLE`) |
| FR12 | 2027-03-02 | 2026-07-01(판매종료일 없음) | BD-2026-07 · 2026-07-01 | CURRENT | `REVIEW_REQUIRED` (`OFFERING_NOT_UNIQUE`, 같은 상품에 2027-01-01 판매개시 FOUR 판매버전 존재) |

V65 룰셋: 4년 — 판매개시일 2027-01-01~2028-12-31, 기초서류 BD-2027-01, 최대 48회, 한도 선지급 총액 100%·유지관리 월 1.5%·유지관리 총액 100%. 7년 — 판매개시일 2029-01-01부터, 기초서류 버전 무관, 최대 84회, 장기유지 61~84차월, 한도 선지급 총액 100%·유지관리 월 0.8%·유지관리 총액 100%·장기유지 월 0.4%. 한도율은 계약체결비용 대비 %이며 '계약체결비용 이내'는 100으로 옮겼다. 61차월 시작은 `PROJECT_ASSUMPTION`(운영정책서 제47조)이다.

---

# 제5장 수수료 항목과 정책 시드

## 12. 수수료 항목

| item_code | item_name | cashflow_type | category | effective_from |
|---|---|---|---|---|
| BASE_COMMISSION | 기본 모집수수료 | PAYMENT | SALES | 2026-01-01 |
| MAINTENANCE_COMMISSION | 유지수수료 | PAYMENT | MAINTENANCE | 2026-01-01 |
| INCENTIVE | 시책수수료 | PAYMENT | INCENTIVE | 2026-01-01 |
| MANAGEMENT_COMMISSION | 관리자수수료 | PAYMENT | MANAGEMENT | 2026-01-01 |
| SETTLEMENT_SUPPORT | 경력설계사 정착지원금 | PAYMENT | SUPPORT | 2026-01-01 |
| NEWCOMER_SUPPORT | 신인활동지원비 | PAYMENT | SUPPORT | 2026-01-01 |
| COMMON_COST | 모집 관련 공통비 | PAYMENT | COST | 2026-01-01 |
| ADJUSTMENT | 정정·조정 | PAYMENT | ADJUSTMENT | 2026-01-01 |
| CLAWBACK | 환수 | DEDUCTION | CLAWBACK | 2026-01-01 |
| RECOVERY | 환수금 회수 | DEDUCTION | RECOVERY | 2026-01-01 |
| LONG_TERM_MAINTENANCE | 장기유지수수료 | PAYMENT | MAINTENANCE | 2027-01-01 (시드 값. 항목 유효일이며 분급 체계 적용은 판매버전 `fee_regime_code`로 정한다, REG-19) |

표의 `item_name`은 설명용이다. 시드의 표시명은 "FC 기본수수료", "유지수수료(현행)", "정착지원금(경력)", "공통비 귀속분", "회수" 등으로 조금 다르다(V3).

## 13. 정책코드 규칙

```text
REG-*: 규제 룰셋
INS-*: 원수사 지급정책
GA-*: FGC 내부 지급정책
ASM-*: 프로젝트 가정
```

예:

| policy_code | policy_type | source_class | 현재 ACTIVE 버전 | 출처 |
|---|---|---|---:|---|
| `REG-CAP-INS-2026-V1` | CAP_1200 | REGULATORY | 없음 — v3 RETIRED (유산, §15) | V3·V46·V52·V63 |
| `REG-CAP-INS-2021-V2` | CAP_1200 | REGULATORY | v3 (2021~2026, 0%) | V6_1·V46·V52 |
| `REG-CAP-INS-2027-V1` | CAP_1200 | REGULATORY | v3 (2027~, 3%) | V6_1·V46·V52 |
| `REG-CAP-GA-2026-V1` | CAP_1200 | REGULATORY | v3 (2026-07-01~) | V3·V46·V52 |
| `INS-CUR-2026-V1` | CURRENT_COMMISSION | INSURER_RULE | v1 | V3 |
| `GA-CUR-2026-V1` | CURRENT_COMMISSION | GA_POLICY | v1 | V3 |
| `GA-ALLOC-COMMON-2026-V1` | ALLOCATION | GA_POLICY | v1 | V3 |
| `INS-REFUND-2026-V1` / `INS-REFUND-2026-SAV-V1` | REFUND_RATE | INSURER_RULE | v1 / v1 | V3 / V42 |
| `ASM-RECON-ZERO-2026-V1` | RECONCILIATION_TOLERANCE | PROJECT_ASSUMPTION | v1 | V3 |
| `STATUS-MONTH-RULE-2026` | SCHEDULE_ELIGIBILITY | PROJECT_ASSUMPTION | v2 | V2·V3 |
| `ARB-APPLICABILITY-2026` | ARBITRAGE | REGULATORY | v2 (적용일 2026-03-01, 합산 36개월, 3주년 당일 제외) | V45_1·V56 |

허용 `policy_type`: CURRENT_COMMISSION, FOUR_YEAR_COMMISSION, SEVEN_YEAR_COMMISSION, CAP_1200, ALLOCATION, REFUND_RATE, CLAWBACK, RECONCILIATION_TOLERANCE, SCHEDULE_ELIGIBILITY, ARBITRAGE (V45). 접두어 규칙의 기존 예외 2건(`STATUS-MONTH-RULE-2026`, `ARB-APPLICABILITY-2026`)은 적용된 마이그레이션이라 이름을 바꾸지 않는다. 새 정책은 접두어를 지킨다.

`policy_version.source_class` 전용 컬럼에 다음 값 중 하나를 필수 저장한다.

```text
REGULATORY
INSURER_RULE
GA_POLICY
PROJECT_ASSUMPTION
```

`source_refs`는 문서번호·파일명·공문번호 등의 근거목록으로만 사용하고,
정책 분류를 나타내는 문자열 토큰은 저장하지 않는다.

## 14. 현행 수수료 규칙

운영정책서 제19~20조의 세 패턴을 `commission_rule`에 행 단위로 적재한다.

필수값:

- 지급단계
- 보험회사 또는 상품 판매버전
- 설계사 직급
- 수수료 항목
- 수수료 구성유형 `fee_component_type`
- 시작·종료 회차
- 계산형식
- 기준코드
- 요율
- 지급조건
- 우선순위
- 정책버전

### 기준코드

| basis_code | 설명 |
|---|---|
| `MONTHLY_EQUIVALENT_FIRST_PREMIUM` | 월납환산 초회보험료 |
| `ACQUISITION_COST` | 계약체결비용 |
| `CONFIRMED_INSURER_AMOUNT` | 원수사 확정 수입액 |
| `FIXED_AMOUNT` | 정액 |

## 15. 1,200% 룰셋

### 보험회사→GA

```text
payment_stage = INSURER_TO_GA
2021-01-01~2026-12-31 : compliance_deduction_pct = 0
2027-01-01~           : compliance_deduction_pct = 3
(채널 구분 없음 — FGC는 대면 모집만, TM·방송 2028-01-01 특례 없음. SRC-036)
공통: first_year_months = 12, premium_multiplier = 12, warning_usage_pct = 90
```

3%는 자동 정액공제가 아니라 REG-10 목적·증빙을 충족한 실제 준법경영비의 최대 공제율이다. 공제액 = `min(증빙된 실제 준법경영비, 월납환산 초회보험료 × 3%)`이며 원수사→GA 2027-01-01 이후 체결 계약에만 쓴다(`CapCalculationResult`). GA→FC는 0%(DB CHECK `ck_cap_compliance_stage`). 증빙참조를 별도 검증한다.

### GA→설계사

```text
payment_stage = GA_TO_FC
contract_date_from = 2026-07-01
first_year_months = 12
premium_multiplier = 12
compliance_deduction_pct = 0
warning_usage_pct = 90
```

주의(WARNING) 판정: `잔여 한도 ≤ (100 − warning_usage_pct)% × (월납환산 × 12)`. 기준액이 한도가 아니라 연간 기준액이므로 80% 공제 상품은 사용률 90% 전에 주의가 된다(#490).

### 항목 분류

| item_code | inclusion_status | attribution_method | 제외유형 코드 |
|---|---|---|---|
| BASE_COMMISSION | INCLUDED | DIRECT | — |
| MAINTENANCE_COMMISSION | INCLUDED (초년도 구간 지급분) | DIRECT | — |
| MANAGEMENT_COMMISSION | INCLUDED | DIRECT | — |
| INCENTIVE | INCLUDED | DIRECT | — |
| SETTLEMENT_SUPPORT | INCLUDED | SETTLEMENT_SUPPORT_MONTHLY | — |
| NEWCOMER_SUPPORT | EXCLUDED(요건·증빙 충족 시) / 아니면 REVIEW_REQUIRED | MANUAL_REVIEW | `NEW_AGENT_SUPPORT` |
| COMMON_COST | INCLUDED | APPROVED_ALLOCATION | — |
| ADJUSTMENT | REVIEW_REQUIRED | MANUAL_REVIEW | — |
| CLAWBACK | 1,200% 누계에 영향 없음(gross). 차익거래에서만 차감 | DIRECT | — |

- 환수(`DEDUCTION`·`CLAWBACK`)는 1,200% 지급 누계를 줄이지 않는다. 한 번 지급한 금액은 환수해도 초년도 누계에 남는다(일시 초과도 위반, REG-08). 차익거래는 `지급 − 환수` 순액을 쓴다. 근거: `docs/478_cap_gross_payment.md`, V46.
- 제외유형 코드는 `NEW_AGENT_SUPPORT`와 `COMPLIANCE_3PCT`(원수사→GA만) 두 개다(V50, 조문표 §9.3). 기존 시드의 `cap_rule_item.exclusion_type='NEWCOMER_SUPPORT'`는 같은 뜻의 옛 코드이며 계산에 쓰이지 않는다(조회 화면 표시만). 신규 시드는 `NEW_AGENT_SUPPORT`를 쓰고, 기존 행 정정은 다음 CAP 정책 재버전 때 함께 한다.

신인활동지원비를 `EXCLUDED`로 넣는 시드는 직전 3년의 등록·모집 이력을 구분한 조회결과, 최근 등록일, 등록예정일과 조회기간 시작·종료일, 지원근거 `evidence_ref`를 반드시 가진다. 두 이력 중 하나라도 확인할 수 없으면 `REVIEW_REQUIRED`다. (`REG-21`, `SRC-022`, `SRC-025`)

각 `cap_rule_item`은 실행 가능한 시드에서 `decision_reason`을 반드시 저장한다. `EXCLUDED`이면 `exclusion_type`과 `evidence_required_yn=true`, `APPROVED_ALLOCATION`이면 `allocation_policy_id`가 필수다. REG-11의 허용 제외유형은 적격 신인활동지원 하나다(제7항 제1·2호 녹취·방송송출은 FGC 적용 대상 없음, SRC-036). 그리고 REG-10 준법경영비는 GA→설계사 제외유형으로 재사용하지 않는다.

현재 `db/demo/V6_1__cap_ins_compliance_deduction_correction.sql`은 2021~2026 0%, 2027 이후 3%로 시행일을 보정한다. FGC는 대면 모집만 다루므로 채널별 룰셋을 나누지 않는다(SRC-036). 또한 V3의 기존 활성 룰셋과 보정 룰셋이 겹치므로 `cap_rule_set_id`만으로 조용히 승자를 정하는 방식을 일반 정책선택 규칙으로 승격하지 않는다. 유산 룰셋 폐기와 비중복 범위 정리는 별도 마이그레이션 대상이다. V46·V52가 이 유산 룰셋을 v3로 복제했지만(당시 승자는 `cap_rule_set_id` 순서), `db/demo/V63__retire_legacy_cap_ins_policy_overlap.sql`이 `REG-CAP-INS-2026-V1`의 ACTIVE 버전(v3)을 RETIRED로 전환했다. V6_1의 두 룰셋(2021-V2: 2021-01-01~2026-12-31, 2027-V1: 2027-01-01~)이 유산 범위를 빈틈·겹침 없이 채우므로 새 룰셋은 발행하지 않았고, 자식 행과 과거 `cap_check` 참조는 그대로 보존된다. V63은 대체 룰셋이 범위를 채우지 못하거나 정리 뒤 활성 중복이 남으면 트랜잭션을 중단한다. §40 인수 검사는 V63 적용 뒤에 수행한다(SRC-065).

## 16. 공통비 배부정책

REG-06A의 1,200%룰상 모집 공통비, REG-06B의 유지관리 간접지원경비, REG-06C의 회사별 안분정책을 같은 항목·비율로 합치지 않는다. `약 19%`는 법정 고정률이 아니므로 시드 상수로 넣지 않는다.

```text
allocation_code = GA-COMMON-PREMIUM-PROPORTIONAL
allocation_name = 월 신계약 월납환산보험료 비례 안분   # 현재 시드 표시명: "공통비 월납환산보험료 비례배부"
pool_type = RECRUITING_COMMON_COST
allocation_basis_code = MONTHLY_EQUIVALENT_PREMIUM_PROPORTIONAL
rounding_scale = 0
rounding_mode = HALF_UP
residual_treatment = LARGEST_REMAINDER
approved_basis_ref = SYNTHETIC:COMMON-COST-POLICY-2026-001
```

1차에는 계산된 결과만 `transaction_attribution`에 넣는다.

## 17. 예상 해약환급률표

각 대상 상품·납입기간·채널별로 1~36차월 36행을 넣는다.

### 필수 조합

| 상품 | 납입기간 | 채널 | 80% 공제 여부 |
|---|---:|---|---|
| STD-LIFE-A | 240개월 | FACE_TO_FACE | false |
| STD-LIFE-B | 240개월 | FACE_TO_FACE | true |
| STD-TERM-A | 120개월 | FACE_TO_FACE | true |
| STD-NL-A | 240개월 | FACE_TO_FACE | false |
| STD-SAV-A | 120개월 | FACE_TO_FACE | false |

> STD-SAV-A 행은 #332 로 추가되었다(근거: REG-23 — 36개월 표·상품코드 일치).
> §11 경계시험용 판매버전(V4) 중 CONT-W03 에서 선택 가능한 상품이 환급률표 없이
> 남으면 등록 계약이 월 통합검증 선별에서 검토필요로 빠지기 때문이다.
> STD-TERM-A 의 TM 판매버전은 SRC-036(대면 모집만)으로 V49에서 삭제했다.

환급률은 합성값이지만 다음 조건을 만족해야 한다.

- 0 이상
- 1~36차월 누락 없음
- `source_product_code` = 해당 상품의 `standard_product_code`(V3·V42). 원수사 명세·AI 입력의 원수사 상품코드는 `product.insurer_product_code`로 표준코드에 매핑한 뒤 비교한다(REG-23)
- 유효기간 중복 없음
- 12차월 값 존재
- 변경본은 새 정책버전과 새 헤더로 적재
- `effective_from`과 `source_document_ref` 필수

---

# 제6장 계약·상태·재무 시드

## 18. 계약 필수필드

| 필드 | 규칙 |
|---|---|
| insurer_id | 유효 보험회사 |
| product_offering_id | 계약일에 유효한 판매버전 |
| contract_no | 보험회사 내 유일 |
| contract_date | 적용 규칙 기준일 |
| agent_id | 모집설계사 |
| organization_id | 계약 당시 조직 |
| premium_per_cycle_amount | 0 이상 |
| first_premium_amount | 0 이상 |
| monthly_equivalent_first_premium | 0 이상 |
| payment_cycle_code | MONTHLY·QUARTERLY·SEMI_ANNUAL·ANNUAL·SINGLE·OTHER (V3 C003은 QUARTERLY). 1,200% 기준은 `monthly_equivalent_first_premium` |
| premium_conversion_rule_code | `MONTHLY_AS_IS`, `MONTHLY_TO_QUARTERLY_X3`, `MONTHLY_TO_SEMI_ANNUAL_X6`, `MONTHLY_TO_ANNUAL_X12`, `DIRECT_INPUT` 중 하나(V8 이후 코드, `PremiumConversionRuleCode`). DB CHECK는 없다 |
| payment_term_months | 양수 |
| current_status | 허용 코드 |
| data_origin | SEED |

### 계약번호 형식

```text
FGC-{보험회사코드}-{계약월YYYYMM}-{일련번호4자리}
```

예:

```text
FGC-FGL01-202607-0001
```

목표 구간(§24 T001~T068)은 일련번호 `9001~9068`, DAR-012는 `5001~` 구간을 쓴다.

## 19. 계약상태 사건

필수값:

- 계약
- 사건순번
- 이전상태
- 새상태
- 효력시각
- 수신시각
- 원천시스템
- 원천이벤트키
- 사유코드
- 데이터출처

`contract_status_event.processed_at`은 v2.1.6부터 폐기 예정 호환 컬럼이므로 신규 시드는 `NULL`로 둔다. 처리 결과가 필요한 테스트는 별도 `contract_status_event_processing`에 다음을 적재한다.

현재 V3/V4의 일부 기존 사건은 v2.1.6 이전 시나리오를 보존하기 위해 원본 `processed_at` 값을 가진다. 이는 호환성 데이터일 뿐 신규 작성 예시가 아니다. 후속 시드는 이 값을 복제하지 않고 Job별 처리행을 사용한다.

```text
contract_status_event_id
processing_job
processing_status = SUCCEEDED | FAILED
validation_run_id  = 월검증 연결 시에만 값
processed_at
failure_reason     = FAILED일 때 필수
```

처리이력은 append-only다. 사건×Job의 성공행은 최대 1건이고 실패행은 재시도 횟수만큼 보존한다. 신규 코드가 `contract_status_event.processed_at IS NULL`로 미처리 여부를 판정하는 것을 금지한다.

### 시드 사건

| 사건 | 예 |
|---|---|
| 정상전환 | APPLIED → ACTIVE |
| 미납 | ACTIVE → UNPAID |
| 실효 | UNPAID → LAPSED |
| 부활 | LAPSED → REVIVED |
| 해지 | ACTIVE → TERMINATED |
| 늦게 도착 | effective_at < received_at |
| 순서역전 수신 | 과거 효력 사건이 나중에 수신됨 |

## 20. 계약 재무 스냅샷

차익거래용 월별 행:

| 필드 | 설명 |
|---|---|
| as_of_date | 기준일 |
| contract_month_no | 계약차월 |
| cumulative_paid_premium | 누적 납입보험료 |
| surrender_value | 실제 또는 예상 해약환급금 |
| surrender_value_type | ACTUAL / EXPECTED_TABLE / ESTIMATED |
| refund_rate_table_id | 예상표 사용 시 필수 |
| source_ref | 합성근거 |

GOLDEN 차익거래 계약은 1~36차월을 월별로 넣는다. 37차월 이후 사례는 실제 누적값만 넣고 1~36차월 예상 환급률표를 연장하지 않는다.

### 시드분과 수기 등록분의 구분

위 월별 행은 시드가 넣는다. 화면(FGC-UI-CONT-W03)에서 수기로 등록한 계약은 시드 대상이 아니므로 계약 저장 트랜잭션이 **초회 1행만** 생성한다.

```text
as_of_date              = 계약일
contract_month_no       = 1
cumulative_paid_premium = 계약의 초회보험료
surrender_value         = NULL            # 임의 산정 금지
surrender_value_type    = EXPECTED_TABLE
refund_rate_table_id    = NULL
source_ref              = MANUAL_CONTRACT_INITIAL
```

`source_ref`로 시드 합성분과 수기 등록분을 구분한다. 업무키 `(contract_id, as_of_date, surrender_value_type)` 안에서 `snapshot_version`을 관리하며 UNIQUE는 업무키+버전이다. 기존·최초 행은 기본 버전 1이므로 기존 INSERT 재시도의 중복 방지는 유지된다. 수기 합성 초회보험료 정정은 직전 행을 `previous_snapshot_id`로 연결해 새 버전을 추가하고, 동일 내용 재요청에는 추가하지 않는다. 시드·정규화 적재·ACTUAL·후속 월별 자료는 정정 대상에서 제외한다. 근거분류는 운영정책서 제7조 예외조항의 `PROJECT_ASSUMPTION`이다.

---

# 제7장 실제 지급명세·지급 건 시드

## 21. 원수사 명세 묶음

`statement_batch`는 보험회사·정산월·명세종류별로 만든다.

예:

```text
insurer = FGL01
settlement_month = 2026-08-01
statement_type = INSURER_COMMISSION
external_statement_no = SYN-FGL01-202608-COMM
received_on = 2026-09-05
source_ref = SYNTHETIC:FGL01:202608:COMMISSION
data_origin = SEED
```

기존 시드 형식(바꾸지 않음): `source_ref = {보험사}-{YYYYMM}-COMMISSION`, `external_statement_no`는 비어 있다(V3·V4). 2차 AI 문서 입력으로 적재한 묶음은 `data_origin='AI_INTAKE'`, 전체 대체본은 `version_no`·`supersedes_statement_batch_id`로 연결한다(제안 DDL — Flyway 반영 전까지 미구현, FUN-068).

## 22. 지급 건 작성순서

모든 `commission_transaction`은 `DRAFT`로 생성한다.

```text
INSERT DRAFT
→ transaction_attribution INSERT
→ 귀속합계 검증
→ 상태를 CONFIRMED로 UPDATE
```

### 업무키

```text
{source_type}:{보험회사}:{정산월}:{원천행번호}
```

예:

```text
INSURER_STATEMENT:FGL01:202608:000001
GA_MANUAL_PAYMENT:202607:A-FC-001:0001
```

기존 시드 형식(바꾸지 않음): 원수사 `INS-{보험사}-{YYYYMM}-{4자리}`, GA 지급 `GA-{YYYY}-{MM}-{4자리}`, 차익거래 `ARB475-…`. 신규 시드는 위 형식 또는 `SYNTHETIC:DAR012:…`(§50)를 쓰고 한 파일 안에서 섞지 않는다.

필수 추가 칸:

- `installment_no` — 원천에 회차가 있으면 반드시 넣는다. 없으면 NULL이고 대사는 `REVIEW_REQUIRED`다(V18). 확정 후에는 바꿀 수 없다. V35 백필은 그때까지의 행만 한 번 채웠으므로 **이후 시드(2차 생성기 포함)는 회차를 직접 넣어야 한다.**
- `GA_MANUAL_PAYMENT`는 `source_contract_id`·`source_sequence`로 자연키를 만든다(V9).

## 23. 계약귀속

아래 블록은 귀속유형별 **차이 필드만 보인 부분 예시**다. 모든 실행용 행에는 공통으로 `commission_transaction_id`, `attribution_seq`, `attribution_scope`, `attribution_date`, 그 날짜의 월초인 `attribution_month`, `attributed_amount`, `inclusion_status_snapshot`, `attribution_method`가 필요하다.

직접수수료:

```text
attribution_method = DIRECT
attributed_amount = 지급 건 전액
```

정착지원금:

```text
attribution_scope = CONTRACT
attribution_method = SETTLEMENT_SUPPORT_MONTHLY
attribution_date = 실제 계약 귀속일
allocation_basis_snapshot = 모집월 신계약과 보험료 비율   # 지급월 아님 (REG-20, SRC-025)
```

신인활동지원비의 계약 미귀속 제외처리:

```text
attribution_scope = AGENT
contract_id = NULL
agent_id = 대상 신인 설계사
attribution_method = NEWCOMER_NON_CONTRACT
inclusion_status_snapshot = EXCLUDED
evidence_ref = 적격·경력조회·지원기간 증빙
```

공통비:

```text
attribution_method = APPROVED_ALLOCATION
allocation_policy_id = 유효 정책
allocation_basis_snapshot = 비용풀·대상계약·비율·반올림 결과
```

### 필수 불변조건

```text
SUM(transaction_attribution.attributed_amount)
= commission_transaction.amount
```

- `CONTRACT` 귀속은 `contract_id`와 `attribution_date`가 필수다.
- `AGENT` 귀속은 신인활동지원비의 `EXCLUDED` 또는 `REVIEW_REQUIRED`에 한정한다.
- 실제 지급의 초년도 판정은 `attribution_date`로 하고 `attribution_month`는 월 집계에만 사용한다.

`REVIEW_REQUIRED` 귀속이 있으면 지급 건을 확정하지 않는다.

---


## 23-1. 수수료 금액 반올림

모든 `RATE` 계산의 기대값은 다음으로 고정한다.

```text
expected_amount = ROUND(basis_amount × rate_pct ÷ 100, 0, HALF_UP)
```

- 상세행별 원 단위 반올림 후 합산
- `rounding_scale = 0`
- `rounding_mode = HALF_UP`
- Java `BigDecimal.setScale(0, RoundingMode.HALF_UP)`
- PostgreSQL 양수 numeric은 `round(value, 0)`
- 금액 허용오차 0원이므로 반올림 규칙이 다른 결과는 정상 대사로 인정하지 않는다.

# 제8장 GOLDEN 시나리오



## 24. 시나리오 ID ↔ 계약번호 매핑

GOLDEN 계약은 다음과 같이 전용 구간을 사용한다. 하나의 계약이 서로 다른 주요 실패 시나리오를 겸하지 않게 한다.

> **번호 주의.** `C001~C006`은 이미 V3 데모 계약의 별칭으로 쓰이고 있다(V3 주석, 테스트 주석). 아래 목표 구간은 이와 섞이지 않도록 **`T001~T068`**로 부른다. V3 별칭과 목표 시나리오의 대응: C001→CAP-01, C002→CAP-02, C003→CAP-03, C004→CAP-04(확정 시도 시), C006→조정 검토필요(신인 증빙 사례 아님), C005→차익거래(실효). 현행 정답은 §31-1.

| 목표 구간 | 시나리오 |
|---|---|
| T001~T012 | CAP-01~CAP-12 |
| T013~T019 | SCH-01~SCH-07 |
| T020~T029 | REC-01~REC-10 |
| T030~T034 | ARB-01~ARB-05 |
| T035~T039 | SET-NORMAL-01~05 |
| T040~T044 | SET-STRESS-01~05 |
| T045~T048 | COMMON-01~04 |
| T049~T055 | JRN-01~JRN-07 |
| T056~T057 | RND-01~02 |
| T058~T060 | FOUR-01~03 |
| T061~T063 | SEVEN-01~03 |
| T064 | EXC-01 |
| T065~T068 | REC-11~REC-14 (수취인 짝짓기, §30 — 나중에 추가된 시나리오) |

계약번호는 §18 형식 `FGC-{보험회사코드}-{계약월}-{일련번호4자리}`를 따르고 일련번호 `9001~9068`을 목표 구간 전용으로 예약한다.
각 계약의 기대결과는 후속 `golden_expected_assertions.sql`에서 시나리오 ID로 조회한다.

## 25. 1,200% 기본 수치


모든 기본 사례의 월납환산 초회보험료:

```text
100,000원
기본 한도 = 100,000 × 12 = 1,200,000원
```

판정식: `산입 수수료등 + 12차월 예상 해약환급금(80% 공제 상품만) − 준법경영비 공제액 ≤ 월납환산 초회보험료 × 12`. 저장하는 `limit_amount`는 이를 한도 쪽으로 옮긴 값이다: `max(0, 월납×12 − 환급금 + 준법경영비)`. 예: STD-LIFE-B(12차월 24%) 월납 100,000 → 환급금 288,000, 한도 912,000. V3 주석의 "한도에 더해진다"는 표현은 틀렸다(파일은 고칠 수 없음, #468).

## 26. 한도 시나리오

| ID | 입력 | 기대결과 |
|---|---|---|
| CAP-01 | 포함액 890,000 | NORMAL, 사용률 74.166667% |
| CAP-02 | 포함액 1,100,000 | WARNING, 사용률 91.666667% |
| CAP-03 | 포함액 1,200,000 | WARNING, 잔여 0 |
| CAP-04 | 포함액 1,250,000 | VIOLATION, 초과 50,000 |
| CAP-05 | 신인활동지원 2,000,000 제외증빙 완비 + 포함액 800,000 | NORMAL |
| CAP-06 | 신인활동지원 증빙누락 | REVIEW_REQUIRED, 확정 차단 |
| CAP-07 | 2026-06-30 계약의 GA→FC 지급 | `NOT_APPLICABLE` 저장(금액 칸 NULL, 사유 스냅샷 필수 — V48·V51), 예외 없음 |
| CAP-07b | 저축성 상품 계약 | `NOT_APPLICABLE`(REG-01 보장성 한정) |
| CAP-08 | 2026-07-01 계약의 GA→FC 지급 | 룰 적용 |
| CAP-09 | 실제 지급을 초년도에 하고 due_date만 13차월 | 위반 또는 데이터품질 예외 |
| CAP-10 | 환급률표 대상인데 12차월 값 없음 | REVIEW_REQUIRED |

### CAP-01 상세

```text
FC 기본수수료      650,000
팀장수수료          40,000
지사장수수료        30,000
본부장수수료        20,000
시책               100,000
공통비 귀속          50,000
합계               890,000
```

### CAP-04 상세

```text
기본·관리자        800,000
고액 시책           300,000
정착지원금 귀속      150,000
합계              1,250,000
```



## 26-1. 초년도 경계 시나리오

계약일 `2026-07-10`:

| 시나리오 | 귀속일 | 기대 |
|---|---|---|
| CAP-11 | 2027-07-09 | 초년도 포함 |
| CAP-12 | 2027-07-10 | 초년도 제외 |

`attribution_month=2027-07-01`만으로는 두 결과를 구분할 수 없으므로 반드시 `attribution_date`를 저장한다.

## 26-2. 반올림 시나리오

| ID | 기준금액 | 요율 | 원금액 | 기대금액 |
|---|---:|---:|---:|---:|
| RND-01 | 33,333 | 1.5% | 499.995 | 500 |
| RND-02 | 100,001 | 0.8% | 800.008 | 800 |

상세행을 반올림한 후 합산하며 합계에서 한 번만 반올림하는 방식은 허용하지 않는다.

## 27. 정착지원금 귀속 시나리오

> 이 절과 §29는 운영정책서 제19·20조 패턴(`CUR-LIFE-A` 1,485%, `GA-LIFE-A` 740%, 시책 없음)을 담은 별도 정책버전을 전제로 한다. 현 시드 정책 `GA-CUR-2026-V1`(시책 100% 포함 840%)로 만들면 SET-NORMAL은 95% WARNING이 된다. 목표 시드를 만들 때 정책코드를 `GA-CUR-LIFE-A-2026-V1`처럼 따로 둔다.

### SET-NORMAL — 정상 운영정책

지급액 2,400,000원, 신계약 월납환산보험료:

```text
100,000 / 100,000 / 200,000 / 300,000 / 100,000
합계 800,000
```

귀속 기대값:

| 계약 | 월납환산 | 기본 GA 지급 740% | 지원금 귀속 | 총 산입액 | 한도 | 기대 |
|---|---:|---:|---:|---:|---:|---|
| T035 | 100,000 | 740,000 | 300,000 | 1,040,000 | 1,200,000 | NORMAL |
| T036 | 100,000 | 740,000 | 300,000 | 1,040,000 | 1,200,000 | NORMAL |
| T037 | 200,000 | 1,480,000 | 600,000 | 2,080,000 | 2,400,000 | NORMAL |
| T038 | 300,000 | 2,220,000 | 900,000 | 3,120,000 | 3,600,000 | NORMAL |
| T039 | 100,000 | 740,000 | 300,000 | 1,040,000 | 1,200,000 | NORMAL |

모든 계약의 사용률은 86.666667%다.

### SET-STRESS — 의도적 전 계약 위반

지급액 4,000,000원은 T040~T044에 `500,000 / 500,000 / 1,000,000 / 1,500,000 / 500,000`으로 귀속한다.
기본 지급 740%와 합산하면 각 계약은 한도의 103.333333%가 되어 모두 `VIOLATION`이어야 한다.
이 시나리오는 정상 회사정책이 아니라 한도차단 데모 전용이다.

## 28. 공통비 시나리오

공통비 700,000원, 월납보험료:

```text
100,000 / 100,000 / 200,000 / 300,000
```

기대 귀속:

```text
100,000 / 100,000 / 200,000 / 300,000
```

오류 시나리오:

- 합계 699,999원 → 지급 건 확정 차단
- 대상월 다른 계약 포함 → DATA_QUALITY
- 정책버전 없음 → ALLOCATION_EVIDENCE_MISSING
- 확정 후 귀속행 수정 → DB 오류

## 29. 스케줄 시나리오

| ID | 정책 | 기대 |
|---|---|---|
| SCH-01 | CUR-LIFE-A / INSURER_TO_GA | 총 1,485%, 회차 정확 |
| SCH-02 | GA-LIFE-A / GA_TO_FC | 직급별 행과 총액 정확 |
| SCH-03 | 동일 입력 두 번 실행 | 두 번째 신규행 0 |
| SCH-04 | 정책 없음 | POLICY_MISSING |
| SCH-05 | 동일 우선순위 정책 2개 | POLICY_DUPLICATE |
| SCH-06 | 정책 변경 | 기존 버전 유지, 신규 스케줄 버전 생성 |
| SCH-07 | 현행·4년·7년 비교 | 세 scenario가 동시에 활성 |

## 30. 대사 시나리오

| ID | 예상 | 실제 | 기대결과 |
|---|---:|---:|---|
| REC-01 | 650,000 | 650,000 | MATCHED |
| REC-02 | 650,000 | 649,999 | AMOUNT_DIFFERENCE |
| REC-03 | 650,000 | 없음 | ACTUAL_MISSING |
| REC-04 | 없음 | 100,000 | EXPECTED_MISSING |
| REC-05 | 1행 | 동일 실제 2행 | DUPLICATE |
| REC-06 | A-FC-001 | 원수사코드가 A-FC-002로 매핑 | AGENT_MISMATCH |
| REC-07 | 13회차 | 실제 14회차 | INSTALLMENT_MISMATCH |
| REC-08 | 해지계약 지급 | 실제 100,000 | INVALID_CONTRACT_PAYMENT |
| REC-09 | 정책버전 불일치 | 금액 같음 | POLICY_VERSION_ERROR |
| REC-10 | 원장 불균형 | 금액 같음 | JOURNAL_IMBALANCE |

매칭 월은 `schedule_line.due_month = commission_transaction.settlement_month`로 비교한다.

### 수취인 짝짓기 시나리오

관리자수수료는 같은 계약·항목·회차에 수취인이 셋이다(팀장 40% · 지사장 30% · 본부장 20%). 짝짓기 규칙(운영정책서 제36조)을 검증하는 시나리오를 둔다.

| ID | 예상 | 실제 | 기대결과 |
|---|---|---|---|
| REC-11 | 팀장 40,000 · 지사장 30,000 · 본부장 20,000 | 동일 수취인 3건, 동일 금액 | 결과 3행 모두 `MATCHED` |
| REC-12 | 위와 같음 | 팀장 건만 없음 | 팀장 `ACTUAL_MISSING`, 나머지 2행 `MATCHED` |
| REC-13 | 위와 같음 | 지사장에게 70,000 **1건**(자기 몫 30,000 + 팀장 몫 40,000), 팀장 없음 | 결과 3행: 팀장 `ACTUAL_MISSING`(40,000↔0), 지사장 `AMOUNT_DIFFERENCE`(30,000↔70,000), 본부장 `MATCHED`. 계약 합계가 90,000으로 같아도 `MATCHED`로 묶지 않는다 |
| REC-14 | 위와 같음 | 팀장 40,000이 미등록 설계사에게 지급 | 남은 예상 1명·실제 1명 → `AGENT_MISMATCH` |

합계만 비교하면 REC-13·REC-14가 전부 `MATCHED`로 통과해버린다. 결과 건수가 아니라 **수취인별 행이 맞는지**를 인수조건으로 삼는다.

## 31. 차익거래 시나리오

### ARB-01 정상

```text
순누적 지급수수료    900,000
지급예정 수수료      200,000
누적 납입보험료    1,200,000
해약환급금                  0
초과액 = 0
기대 = CLEAR
```

### ARB-02 12차월 후보

```text
순누적 지급수수료    900,000
지급예정 수수료      200,000
해약환급금           300,000
누적 납입보험료    1,200,000
초과액 = 200,000
기대 = CANDIDATE
```

### ARB-03 차감 반영

```text
총 지급수수료      1,100,000
확정 차감액          200,000
순누적 지급수수료    900,000
지급예정 수수료      100,000
누적 납입보험료    1,200,000
기대 = CLEAR
```

### ARB-04 37차월

```text
contract_month_no = 37
EXPECTED_TABLE 환급금 합산 = 금지
실제 누적보험료와 순수수료로 계속 계산
```

### ARB-05 자료부족

- 대상 상품인데 환급률표 없음
- 상품코드 불일치
- 스냅샷 월 누락

기대: `REVIEW_REQUIRED`와 예외 생성

## 31-1. 현행 demo fixture 정답표

V4 주석의 정답표는 Flyway 체크섬 때문에 고칠 수 없다(SRC-027 D-04). 현행 기대값은 이 표를 따른다(정적 계산, 테스트로 고정된 것은 표시).

| 계약 | 시나리오 | 기대 | 고정 테스트 |
|---|---|---|---|
| FGC-FGL01-202607-0001 (C001) | GA→FC 확정 산입 890,000 | NORMAL, 74.166667% | — |
| …-0002 (C002) | 1,100,000 | WARNING, 91.666667% | — |
| …-0004 (C003) | 1,200,000 | WARNING, 잔여 0 | — |
| …-0005 (C004) | 확정 1,000,000 + DRAFT 시책 250,000 | 확정 시도 시 1,250,000 → 차단(104.166667%) | — |
| …-0003 (C006) | 확정 980,000 + 조정 150,000 검토필요 | 조정 건 확정 불가 | — |
| FGC-FGN01-202605-0001 (C005) | 지급 640,000 − 납입 100,000 | 차익거래 초과 540,000 | — |
| R1~R8 | V4 주석 정답표 | 같음. 다만 R5는 관리자·시책 `ACTUAL_MISSING`도 함께 생긴다(수취인 짝짓기, 운영정책서 제36조) | — |
| A1 FGC-FGL02-202601-0001, A2 FGC-FGL01-202601-0001 | 차익거래 시행일(2026-03-01) 전 계약 | GA→FC 차익거래 **CLEAR(적용대상 아님)**, 예외 0. V4 주석의 CANDIDATE는 V45_1 이후 틀림 | `ArbitrageManualCheckIntegrationTest` |
| A1′ FGC-FGL02-202603-0475 | 890,000 + 환급금 120,000 − 납입 600,000 | CANDIDATE 410,000 | `ChangedContractArbitrageIntegrationTest` |
| A2′ FGC-FGL01-202603-0475 | 890,000 − 600,000 (일반 상품) | CANDIDATE 290,000 | 상태만 고정(`ArbitrageManualCheckIntegrationTest`) |
| A3 FGC-FGL03-202608-0001 | 240개월납·환급률표 120개월뿐 | REVIEW_REQUIRED, REFUND_TABLE_MISSING | — |
| 1,200% A1(원수사→GA) | 80% 공제 상품 | 12차월 환급금 288,000, 한도 912,000 | `CapCalculatorIntegrationTest` |
| G1 FGC-FGL01-202703-0001 | 원수사→GA 2027 계약 | 준법경영비 3% 공제 대상(증빙 3,000 → 한도 1,203,000) | `CapCalculatorIntegrationTest` |
| G2 FGC-FGL02-202611-0001 | 2026 계약·2027 판매버전 | REVIEW_REQUIRED, POLICY_MISSING | — |

A1·A2(및 0475 복제본)의 관리자수수료는 팀장 1명에게 90,000 1행으로 들어가 있다(V4). 1월·3월 GA→FC 대사 결과는 수취인 짝짓기 규칙에 따라 불일치가 정상이다. 차익거래 합계에는 영향 없다.

## 32. 원장 시나리오

| ID | 입력 | 기대 |
|---|---|---|
| JRN-01 | 차변 650,000 / 대변 650,000 | POSTED |
| JRN-02 | 차변 650,000 / 대변 640,000 | POSTED 차단 |
| JRN-03 | 원분개 직접수정 | 차단 |
| JRN-04 | 원분개 → 역분개 → revision 2 재기표 | 성공 |
| JRN-05 | 동일 원천·동일 revision 중복 | UNIQUE 오류 |
| JRN-06 | FINALIZED 실행의 상세행 수정 | 차단 |
| JRN-07 | MATCHED 후 예상·실제 분개 | 둘 다 POSTED 유지, EXPECTED/ACTUAL 보고축 분리 |

## 33. 예외 시나리오

- 같은 검증결과 재실행 시 예외 중복 0건
- 같은 실행 재시도: 예외 업무건 +0, 검출 이력 +0 `[SRC-032]`
- 같은 월의 새 실행에서 같은 문제 재검출: 예외 업무건 +0, 검출 이력 +1 `[SRC-032]`
- 다른 월의 같은 계약·같은 문제: 예외 업무건 +1 `[SRC-032]`
- 해결(`RESOLVED`)된 문제가 다시 검출되면 기존 업무건을 `NEW`로 재오픈하고 검출 이력에 재오픈 여부를 남긴다. 반려(`REJECTED`)된 건은 증거가 같으면 닫힌 채 남는다(운영정책서 제44조) `[SRC-032]`
- 상태전이:
  - NEW → IN_REVIEW → RESOLVED / REJECTED 성공
  - NEW에서 바로 해결 조치 → 거절(운영정책서 제41조, `ExceptionActionType`)
  - REJECTED → REOPEN → IN_REVIEW 성공
  - RESOLVED 원장 정정 예외는 재오픈하지 않고 새 정정 예외를 만든다
- 처리사유 없음 → 저장 차단
- 해결조치와 근거참조 보존

---

# 제9장 2차 GOLDEN 시나리오

## 34. 4년 분급

월납보험료 100,000원, 계약체결비용 3,000,000원:

- 계약체결비용 3,000,000원(월납보험료의 3,000%)은 합성 상품의 `PROJECT_ASSUMPTION`이며 법정 고정값이 아니다. 실제 연계 시 보험사·상품·판매버전별 기초서류 값(`INSURER_RULE`)으로 대체한다. (`REG-02`, `SRC-016`, `SRC-022`)
- 선지급 600%와 월 1.5% 지급률은 FGC의 `GA_POLICY` 시드값이다. 1.5%와 최대 48개월은 규제상 상한이지만, 1차월 시작은 근거 없음 — 확인 필요이므로 `PROJECT_ASSUMPTION`으로 분리한다. (`REG-02`, `REG-04`, `SRC-003`, `SRC-022`)

```text
계약체결비용 3,000,000
선지급       600,000                     → 한도 3,000,000 대비 20.0%
유지관리 월   45,000  (1~48차월)          → 월 한도 45,000 대비 100.0%
유지관리 총액 2,160,000                   → 총액 한도 3,000,000 대비 72.0%
초년도 예정  1,140,000                    → 1,200% 한도 1,200,000 대비 95.0%
```

검증:

- `PROJECT_ASSUMPTION`에 따라 1~48차월 48개 동일금액 행을 만들고 49차월 행은 만들지 않는다. 이 시작회차를 규제 확정값으로 표시하지 않는다. (`REG-04`, `SRC-003`, `SRC-022`)
- `acquisition_cost_check_detail`에는 선지급 총액(`UPFRONT_TOTAL`), 월별 유지관리 한도(`MAINTENANCE_MONTHLY`), 유지관리 총액(`MAINTENANCE_TOTAL`)을 각각 저장한다
- 초년도 1,200% 사용률은 계약체결비용 상세에 섞지 않고 별도 `cap_check`에 저장한다
- 세 한도를 서로 차감하지 않는다. `계약체결비용 − 선지급 − 유지관리총액` 같은 계산은 하지 않는다
- 시책 100,000 추가 시 초년도 1,240,000 → `VIOLATION` (초년도 여유가 60,000원뿐)
- 잔여액을 48개월로 나눈 50,000원은 월 1.5% 초과이므로 사용 금지
- 2026 기초서류 상품에 4년 룰 자동적용 금지

## 35. 7년 분급

- 계약체결비용 3,000,000원은 4년 분급과 같은 `PROJECT_ASSUMPTION`이다. (`REG-02`, `SRC-016`, `SRC-022`)
- 월 0.8%·최대 84개월과 5년 이상 유지 후 월 0.4%는 규제상 상한·요건이다. 1~84차월과 61~84차월이라는 실제 시드 구간은 최초 지급회차 근거가 없어 `PROJECT_ASSUMPTION`으로 분리한다. (`REG-03`, `REG-05`, `SRC-022`)

```text
계약체결비용 3,000,000
선지급       600,000                      → 한도 3,000,000 대비 20.0%
유지관리 월   24,000  (1~84차월)           → 월 한도 24,000 대비 100.0%
유지관리 총액 2,016,000                    → 총액 한도 3,000,000 대비 67.2%
장기유지 월   12,000  (61~84차월)          → 월 한도 12,000 대비 100.0%
장기유지 총액   288,000
두 총액 합계 2,304,000                     → 보수적 총액한도 대비 76.8%
초년도 예정    888,000                     → 1,200% 한도 1,200,000 대비 74.0%
```

검증:

- `PROJECT_ASSUMPTION`에 따라 유지관리는 1~84차월, 장기유지는 61~84차월의 각 종류별 동일 월금액 행을 만든다. (`REG-03`, `REG-05`, `SRC-022`)
- 61~84차월은 유지관리 24,000 + 장기유지 12,000 = 월 36,000원이 동시 지급된다
- **두 수수료의 월 상한을 각각 따로 비교한다.** 합산액 36,000원(월 1.2%)을 하나의 상한과 비교하지 않는다
- 장기유지 61차월 시작은 REG-05의 “계약이 5년 이상 유지” 문언을 60개월 경과 후로 옮긴 FGC 시드 가정이다. REG-05가 최초 지급회차를 직접 확정하지 않으므로 49차월 또는 61차월을 법정 시작값으로 일반화하지 않는다. (`REG-05`, `SRC-022`)
- 최초 지급회차와 월중 해지·실효·부활 기준은 근거 없음 — 확인 필요이며, 최종 FAQ 또는 회사 지급기준 확보 전 `PROJECT_ASSUMPTION`으로 버전 관리한다. (`REG-03`, `REG-05`, `SRC-022`)
- 세 한도를 서로 차감하지 않는다
- 실효 후 미래회차 HOLD 또는 CANCELLED
- 부활은 자동 소급지급하지 않고 REVIEW_REQUIRED
- 담당설계사 변경일 이후 회차만 새 담당자 귀속

**적재 상태와 전제 (2026-10-22)**

- 4년·7년 시드는 아직 없다. 필요한 것: 4년·7년 정책버전(DRAFT→승격), 판매버전(`fee_regime_code` FOUR_YEAR_2027·SEVEN_YEAR_2029), 계약.
- 계약체결비용 3,000,000원을 담을 칸이 스키마에 없다(계약 테이블·정책 파라미터 어느 쪽인지 미정). 근거 없음 — 확인 필요. 정하기 전에는 `policy_parameter`(상품 판매버전 단위 값)로 두는 안을 기본으로 검토한다 — 프로젝트 가정.
- SCH-07(현행·4년·7년 비교)은 `schedule_purpose='COMPARISON'` 헤더로 만들어 운영 집계(`OPERATIONAL`)와 섞지 않는다(DAR-012).

## 36. 환수

2차 시드에는 실제 공개 환수표를 그대로 쓰지 않고 `PROJECT_ASSUMPTION`으로 단순화한 가상 그룹을 사용한다. 아래 비율은 법정 공통 환수율이나 특정 보험회사 지급기준이 아니다. (`REG-14`, `REG-16`, `SRC-019`, `SRC-020`)

| 정책코드 | source_class | 1~3회 | 4~6회 | 7~12회 | 13~18회 | 19회 이후 | evidence_ref |
|---|---|---:|---:|---:|---:|---:|---|
| `ASM-CLAWBACK-A-V1` | `PROJECT_ASSUMPTION` | 100% | 90% | 60% | 30% | 0% | `SYNTHETIC:CLAWBACK:A` |
| `ASM-CLAWBACK-B-V1` | `PROJECT_ASSUMPTION` | 100% | 80% | 50% | 20% | 0% | `SYNTHETIC:CLAWBACK:B` |
| `ASM-CLAWBACK-C-V1` | `PROJECT_ASSUMPTION` | 100% | 70% | 40% | 10% | 0% | `SYNTHETIC:CLAWBACK:C` |

환수 시드는 다음 원칙을 함께 검증한다. (`REG-13`, `REG-14`, `REG-16`, `SRC-019`, `SRC-020`, `SRC-022`)

1. `INSURER_TO_GA`와 `GA_TO_FC`의 규칙·사건·원장행을 분리한다.
2. 해지·실효 뒤 미래 유지관리수수료를 지급하지 않는 것과 이미 지급한 금액을 환수하는 것을 구분한다. 미래 미지급액은 `clawback_case`나 환수액으로 만들지 않는다.
3. 실제 보험회사별 환수율을 반영할 때는 새 `policy_version`을 만들고 보험회사·상품·항목·계약일·경과회차·책임사유를 모두 저장한다.
4. 적용일 현재 유효한 위탁·위촉계약 또는 회사 지급기준이 없으면 **근거 없음 — 확인 필요**로 표시하고 `REVIEW_REQUIRED`에서 환수 확정을 차단한다. (`SRC-021` 미확보)

---

# 제10장 API·입력 예시

## 37. 계약 입력 예시 (DB 적재용 입력 사실 — 업무키 기준)

> API 요청 본문이 아니다. API 요청은 IF-API-18이며 `insurerId`·`productOfferingId`·`agentId`·`organizationId`·`contractStatus` 같은 대리키를 쓴다.

```json
{
  "insurerCode": "FGL01",
  "productOfferingCode": "STD-LIFE-A:2026-CURRENT-A:FACE_TO_FACE",
  "contractNo": "FGC-FGL01-202607-0001",
  "contractDate": "2026-07-10",
  "agentCode": "A-FC-001",
  "organizationCode": "FGC-T010101",
  "premiumPerCycleAmount": 100000,
  "firstPremiumAmount": 100000,
  "monthlyEquivalentFirstPremium": 100000,
  "paymentCycleCode": "MONTHLY",
  "paymentTermMonths": 240,
  "currentStatus": "ACTIVE",
  "dataOrigin": "SEED"
}
```

## 38. 지급 건 초안 예시 (DB 적재용 입력 사실 — 업무키 기준)

> 아래 §38·§39는 **시드를 적재할 때 필요한 입력 사실**이지 API 요청 본문이 아니다.
> 시드는 대리키(ID)가 생성되기 전에 작성하므로 `recipientAgentCode`·`commissionItemCode`·
> `contractNo` 같은 **업무키**로 쓰고, 테이블 단위로 나눠 적재한다.
>
> **API 요청 본문은 `IF-API-22`를 따르며 형태가 다르다** — `commissionItemId` 등 대리키를 쓰고
> 귀속행을 `attributions[]`로 한 요청에 중첩해 보낸다. 둘을 섞어 쓰지 않는다.
>
> 귀속금액 합계는 지급액과 같아야 한다: §39 `attributedAmount` 100,000 = §38 `amount` 100,000.

```json
{
  "paymentStage": "GA_TO_FC",
  "sourceType": "GA_MANUAL_PAYMENT",
  "sourceBusinessKey": "GA_MANUAL_PAYMENT:202607:A-FC-001:0001",
  "recipientAgentCode": "A-FC-001",
  "commissionItemCode": "INCENTIVE",
  "policyCode": "GA-CUR-2026-V1",
  "settlementMonth": "2026-07-01",
  "dueDate": "2026-07-10",
  "amount": 100000,
  "cashflowType": "PAYMENT",
  "status": "DRAFT",
  "evidenceRef": "SYNTHETIC:INC-STD-100"
}
```

## 39. 귀속 예시 (DB 적재용 입력 사실 — 업무키 기준)

> §38의 지급 건에 `sourceBusinessKey`로 연결한다. API에서는 이 내용이 `IF-API-22` 요청의
> `attributions[]` 원소로 들어간다.

```json
{
  "sourceBusinessKey": "GA_MANUAL_PAYMENT:202607:A-FC-001:0001",
  "attributionSeq": 1,
  "contractNo": "FGC-FGL01-202607-0001",
  "agentCode": "A-FC-001",
  "attributionDate": "2026-07-10",
  "attributionMonth": "2026-07-01",
  "attributedAmount": 100000,
  "inclusionStatusSnapshot": "INCLUDED",
  "attributionMethod": "DIRECT",
  "evidenceRef": "SYNTHETIC:INC-STD-100"
}
```

---

# 제11장 검증 SQL·인수조건

## 40. 입력 정합성

**선행 조건·적용 순서(SRC-027 D-04·SRC-065):** 기존 시드 적용 → `db/demo/V63`으로 CAP 유산 정책 폐기·활성 범위 중복 정리 → 전체 입력 정합성 검사 → §41 생성결과 검사 순서로 진행한다. V63은 V3·V6_1과 이를 복제한 V46·V52의 동일 범위 활성 룰셋 가운데 유산 정책 `REG-CAP-INS-2026-V1`의 ACTIVE 버전(v3)을 RETIRED로 전환하고, 과거 참조와 자식 행은 보존한다. V6_1의 `REG-CAP-INS-2021-V2`·`REG-CAP-INS-2027-V1`이 2021-01-01~무기한을 빈틈·겹침 없이 채우므로 새 비중복 룰셋은 발행하지 않았다(§15). 적용된 Flyway 파일이나 잠긴 자식 행을 직접 고치지 않는다.

정리 전 검사는 결함 파악용으로만 실행할 수 있고 인수 합격으로 처리하지 않는다. 현재 `cap_rule_set_id` 정렬은 이 중복을 우회하는 기존 동작일 뿐 허용 예외나 일반 선택 정책이 아니다. 정리 후에는 같은 범위·유효기간의 활성 중복이 0건임과 계약 경계일의 정책 선택을 확인한다. S04의 시드·발표 데이터 인수 전에 이 선행 조건을 완료한다.

위 선행 작업까지 적용한 뒤 다음 결과가 0건이어야 한다.

- 상위조직 없는 비GA 조직
- 유효기간 역전
- 계약일에 유효하지 않은 상품버전
- 계약일에 해촉된 설계사
- 월납환산보험료 누락
- 환급률표 1~36차월 누락
- 동일 범위의 활성 정책 중복
- 실제명세 계약 미매칭
- 원수사 설계사코드 중복
- 지급 건과 귀속합계 불일치
- `REVIEW_REQUIRED` 지급 건의 확정

## 41. 생성결과 인수조건

| 기능 | 인수조건 |
|---|---|
| 스케줄 | Golden 기대 회차·금액과 정확히 일치 |
| 멱등성 | 동일 재실행 신규행 0 |
| 1,200% | CAP-01~12 기대상태 일치 |
| 차익거래 | 최초 후보차월과 초과액 일치 |
| 원장 | 불균형 POSTED 0 |
| 대사 | REC-01~14 유형·금액 일치(수취인 짝짓기 포함) |
| 예외 | 같은 월·같은 안정 업무키의 `exception_case` 1건, 실행별 `exception_occurrence` 1건. 같은 실행 재시도는 업무건·검출 이력 신규행 0 `[SRC-032]` |
| 확정잠금 | FINALIZED 상세 수정 0 |
| 감사로그 | 핵심 변경 시나리오 기록률 100% |

`audit_log`는 append-only이지만 모든 변경을 자동 기록하는 범용 트리거는 아니다. 감사로그 100%는 서비스가 각 업무변경과 같은 트랜잭션에서 행을 생성했는지 검증하는 **애플리케이션 인수조건**이다.

월 통합검증 실행은 `CREATED/current_step=0`으로 생성하고, 완료 시 `COMPLETED/8`, 담당자 확정 시 `FINALIZED/10`이어야 한다. 9단계 검토대기는 별도 9를 저장하지 않고 `COMPLETED/8`로 표현한다. DB는 상태·단계 조합과 확정 후 불변성을 강제하지만, `CRITICAL` 예외 0건·정책누락 0건·원장균형·상세합계 같은 확정조건은 서비스 테스트로 검증한다.

## 42. 성능 데이터 기대

DAR012 프로파일(§50):

- 정책 조회는 계약별 N+1 쿼리를 만들지 않는다.
- 스케줄 라인은 batch insert를 사용한다.
- 1천·3천·1만2천 계약 단계마다 스케줄 생성과 월검증 시간, 조회 p95를 측정한다(DAR-012, PER-003·004).
- 실행계획에서 주요 조인은 인덱스를 사용한다.
- `schedule_line.due_month` 조건으로 대사한다.
- 테스트결과에 PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)`를 보관한다.

성능목표 숫자는 팀 개발환경을 1회 측정한 뒤 확정한다. 측정 전 임의의 초 단위 목표를 계약하지 않는다.

---

# 제12장 초기화·재현성

## 43. 초기화

DB 안에서 행을 지워 초기화하지 않는다. `audit_log`·`contract_status_event`(append-only), 정책·정책 자식 가드, `exception_action`·`exception_occurrence`, 처리이력, 재무 스냅샷, 확정 실행 결과의 트리거가 DELETE를 막는다. 막지 않게 만들면 그 통제가 시연용으로 약해진다.

| 환경 | 방법 |
|---|---|
| 통합 테스트 | Testcontainers가 매번 빈 DB에 Flyway를 다시 적용한다(`application-test.yml`) |
| 로컬 | DB 컨테이너 볼륨을 지우고 앱을 다시 띄워 Flyway를 처음부터 적용한다. `flyway clean`은 로컬에서만 쓴다 |
| 600인 데이터셋(§50) | 새 DB에 Flyway 적용 → 생성기 실행 → 배치 실행. 같은 DB에 같은 `dataset_id`가 있으면 생성기가 멈춘다 |
| 신한 prd·검증계 | 초기화하지 않는다 |

`golden_reset.sql`은 만들지 않는다. 기대값 검증 SQL(`golden_expected_assertions.sql`)은 유지한다.

## 44. 시간 고정

테스트의 현재시각 의존성을 제거한다.

```text
business_date = 2026-09-10
validation_month = 2026-08-01
```

화면 기본 정산월은 `fgc.demo-month`(`application.yml`, 2026-07)이다. 테스트 시계 값과 다르면 테스트에서 명시한다.

Java에서는 `Clock`을 주입하고 테스트에서 고정시계를 사용한다.

## 45. 난수

DAR012 생성기는 고정 seed를 사용한다(예: `random_seed = 20261118`). Java `SplittableRandom(seed)` 같은 결정적 생성기를 쓰고 `clock_timestamp()`·`now()`를 값에 쓰지 않는다.

같은 seed로 같은 계약수·금액분포가 생성되어야 한다.

---

# 제13장 ERD·요구사항 매핑

## 46. 핵심 테이블

| 업무 | 테이블 |
|---|---|
| 보험회사·조직·설계사 | insurer, organization, agent, agent_insurer_code |
| 상품·판매버전 | product, product_offering |
| 항목·정책 | commission_item, policy_version, policy_parameter, commission_rule |
| 1,200% 정책 | cap_rule_set, cap_rule_item |
| 배부근거 | allocation_policy |
| 환급률 | refund_rate_table, refund_rate_line |
| 계약·상태·재무 | insurance_contract, contract_status_event, contract_status_event_processing, contract_financial_snapshot |
| 실제 돈 | statement_batch, commission_transaction, transaction_attribution |
| 예상 스케줄 | schedule_header, schedule_line |
| 배치 진행 | batch_watermark, validation_run.current_step |
| 1차 검증 | validation_run, validation_target, cap_check, cap_check_detail, arbitrage_check |
| 2차 검증 | acquisition_cost_check, acquisition_cost_check_detail, maintenance_check |
| 담당자·환수 | agent_history, contract_manager_assignment, policy_approval_action, clawback_rule, clawback_case, clawback_line, recovery_transaction |
| 원장 | journal_account, journal_header, journal_line |
| 대사 | reconciliation_run, reconciliation_result, reconciliation_match |
| 예외 | exception_case, exception_occurrence, exception_action |
| 감사 | audit_log |

`exception_occurrence`는 SRC-032(2026-08-18)가 신설한 월 통합검증 실행별 예외 검출 이력이다. `exception_case`는 검증월 기준 안정 업무키의 업무건으로, 같은 결정으로 `reason_code`, `validation_month`, `first/last_detected_run_id`, `first/last_detected_at`, `detection_count` 컬럼이 추가됐다.

`cap_check_detail`은 계산 당시 `item_code`, `item_name`을 반드시 스냅샷으로 저장한다. `schedule_line_id`가 있는 상세행은 `contract_month_no`도 필수이고, 실제 귀속행에서 온 상세는 회차가 없으므로 `NULL`이 허용된다. `acquisition_cost_check_detail`은 초년도 1,200%가 아니라 REG-02·03·05의 계약체결비용 한도 상세만 저장한다.

## 47. 요구사항 연결

| 명세 영역 | 주요 요구사항 |
|---|---|
| 정책 시드 | FUN-011~013, DAR-003, DAR-011 |
| 계약 시드 | FUN-018, DAR-004~005 |
| 상태·재무 | FUN-025~029의 데이터 선반영, FUN-063 |
| 지급 건·귀속 | FUN-031, FUN-033, FUN-065 |
| 현행 스케줄 | FUN-036~040 |
| 월 검증 | FUN-041~044 |
| 원장 | FUN-046~047 |
| 대사 | FUN-048~052 |
| 예외 | FUN-053, FUN-061 |
| 2차 분급 | FUN-037~038, FUN-062, FUN-064 |
| 환수 | FUN-054~056, FUN-084 |
| 600인 데이터셋 | DAR-012, FUN-075·080·085, PER-003·004, TER-011 |
| AI 입력 샘플 | FUN-066~069·078, TER-011, SER-013 |
| 설계사 계정 | FUN-085, SER-002 |
| 협회 등급자료 | FUN-078·079, OI-024·025 |

## 48. 요구사항 정정 메모

- 1차 정책은 시드로만 넣는다. 2차부터 화면 경로(IF-API-75)로 DRAFT 정책을 승인·활성화할 수 있지만, 시드는 계속 §4-1 절차를 따른다.
- `contract_status_event`는 1차 데이터이지만 자동 상태머신은 2차다.
- 실제 지급의 초년도 귀속일은 `attribution_date`(일 단위)이며, 판정식은 `계약일 <= 귀속일 < 계약일 + 1년`이다. `attribution_month`는 월 집계에만 쓴다.
- 공통비는 1차에 계산하지 않고 사전 배부결과를 검증한다.
- 차익거래 `paid_commission_amount`는 순누계로 사용한다.
- 결과 테이블은 시드하지 않는다.

## 49. REG-01~24 시드 추적표

| REG | 필수 입력·정책 시드 | 기대 검증 또는 범위 표시 |
|---|---|---|
| REG-01 | 수수료체계별 `product_offering`·정책버전 | 보장성 대상과 일반손해·자동차 제외를 구분 |
| REG-02 | 계약체결비용, 선지급 규칙 | 선지급 총액 한도 상세 생성 |
| REG-03 | 0.8%·84개월·동일월액 정책, 상태이력 | 월액·총액·유지상태 검증 |
| REG-04 | 4년 특례 상품버전(2027~2028, TM·방송 특례는 SRC-036으로 범위 밖) | 1.5%·48개월과 시행경계 검증 |
| REG-05 | 0.4% 장기유지 규칙 | 5년 유지 전 지급 없음, 61차월 정책경계 검증 |
| REG-06 | 공통비와 간접지원경비를 다른 항목·정책으로 적재 | 승인 안분근거와 배부 전후 총액 일치 |
| REG-07 | 원모집자와 유지관리 담당자 이력 | 회차 기준 수령인 검증, 담당자 누락 검토 |
| REG-08 | 지급단계별 cap 룰셋·초년도 귀속일 | 월납보험료×12, 80% 상품은 12차월 예상 해약환급금을 수수료등에 합산(한도에서 차감, §25) |
| REG-09 | 직·간접 비용 항목과 귀속행 | 명칭이 아니라 실질·귀속근거로 산입 |
| REG-10 | 2027 원수사→GA 룰셋 | GA→FC 0%, 실제 증빙액만 3% 이내 공제 |
| REG-11 | 신인 지원의 증빙 (녹취·방송은 FGC 적용 대상 없음, SRC-036) | 요건충족 제외, 누락 시 `REVIEW_REQUIRED` |
| REG-12 | 해지·실효 사건, 누적보험료·환급금·순수수료 | 전기간 후보검증, 36개월 조건부 환급금 합산 |
| REG-13 | 해지·실효 후 미래 스케줄 | 미래 미지급과 기지급 환수 구분 |
| REG-14 | 합성 회사별 환수율·책임사유 버전 | 규제 고정률로 표시 금지 |
| REG-15 | 구법 정책은 종료·참조용으로만 보존 | 신규 상품에 구 60%·105% 규칙 미적용 |
| REG-16 | 정착지원 약정·귀속·회사 환수조건 | REG-20 귀속과 환수는 별도 정책으로 연결 |
| REG-17 | 대표가입속성·총보험료·수수료 구성 입력 | 산식 검산값이며 법정 공시 완료로 표시 금지 |
| REG-18 | 외부 등급·순위 버전(협회 자료 기준일·비교 집합 포함, §53) | 2차 FUN-078·079. 테이블이 Flyway로 들어오기 전에는 비교설명 완료 시나리오를 만들지 않는다. 들어온 뒤에도 '법정 의무 완료'로 표시하지 않는다 |
| REG-19 | 판매개시일·기초서류일/버전·체계코드 (판매채널은 대면 하나라 판정 기준 아님, SRC-036) | 계약연도 단독 판정 금지, 경계는 검토대상 |
| REG-20 | 모집월 신계약·보험료 배부기준·무실적 이월 근거 | 계약별 재귀속 후 cap 재검증 |
| REG-21 | 등록예정일·직전 3년 등록/모집 이력·지원기간·증빙 | 등록 이력이 있어도 모집 이력이 없으면 신인 가능, 모집 이력이 있으면 신인 아님 (`SRC-022`, `SRC-025`) |
| REG-22 | 작성자·승인자·근거·버전·우회지급 테스트 | 동일인 승인·ACTIVE 직삽·과거정책 수정 차단 |
| REG-23 | 상품·납입기간·채널별 1~36차월 환급률표 | 누락·중복·코드불일치·12차월 부재 검토 |
| REG-24 | 상품위원회 승인일·문서참조·거버넌스 상태 | 메타데이터 연결만 검증, 위원회 운영 완료로 표시 금지 |

REG-17·18·24처럼 FGC가 외부 의무 전체를 구현하지 않는 영역은 “정상 통과” 합성결과를 만들지 않는다. `NOT_APPLICABLE`, `METADATA_ONLY`, `OUT_OF_SCOPE`와 동등한 기대상태를 테스트 설명에 명시한다.

---


# 제14장 승인상태

본 문서는 `v1.0 규제·스키마·추가자료 정합성 보정본(2026-08-10)`에 2026-10-22 정합성 교정(실제 시드·배포 설정·2차 요구사항 r19 대조)을 반영한 판이며 팀에 배포한다. 신인 판정, 분급 시드의 출처 분류, 환수 시드 원칙은 `REG-02~05`, `REG-13`, `REG-14`, `REG-16`, `REG-21`과 `SRC-016`, `SRC-019`, `SRC-020`, `SRC-022`, `SRC-025`를 기준으로 재검토했다.
기준 스키마는 develop `fe6d0e6c`의 Flyway V55(+ demo V56)이다. 이 문서는 전체 GOLDEN 목표 명세이며 현재 `db/demo` 축소 fixture의 구현완료 선언이 아니다.

다음 작업은 본 문서를 기준으로 진행하며, 진행 결과가 본 문서를 바꾸지 않는다.

1. 요구사항명세서 v2.2.2-r19 기준 유지(운영정책서 제53조 문구는 반영됨)
2. T001~T068 전체 GOLDEN 시드 SQL 작성 — **제4-1항의 정책 승격 순서를 반드시 지킬 것**
3. ~~REG-10 유산 룰셋 폐기 마이그레이션 작성~~ — 완료: `db/demo/V63` (일반/TM 시행일 분리는 SRC-036으로 삭제)
4. `golden_expected_assertions.sql` 작성, DAR-012 생성기(§50)·AI 입력 샘플(§52)·협회 등급자료(§53) 작성 — 초기화는 §43(DB 재생성)
5. 전체 회귀테스트 실행

# 제15장 2차 시드 명세 (요구사항 r19 — 초안, 팀 승인 시 확정)

## 50. DAR-012 600인 합성 데이터셋

**위치와 적재 방식 — 프로젝트 가정, 팀 확인 필요**

- Flyway(`db/migration`·`db/demo`)에 넣지 않는다. 이유: ① test 프로필이 `db/demo`를 적용하고 정확한 집합·건수를 단언한다(`ValidationTargetSelectionRepositoryIntegrationTest`, `TmChannelRemovalMigrationIntegrationTest`, Dashboard KPI 테스트) ② `db/demo`는 검증계·신한 prd에 자동 적재된다(§2) ③ Flyway에서는 배치를 돌릴 수 없는데 DAR-012는 "기초 데이터 적재 → 기존 배치 실행" 순서를 요구한다.
- 생성기는 명시적으로 실행하는 별도 진입점으로 둔다(예: `@Profile("dar012")` `ApplicationRunner` 또는 Gradle 태스크). 기본 프로필·test·배포 환경에서는 자동 실행하지 않는다.
- 실행 순서: ① 빈 DB에 Flyway(`db/migration`+`db/demo`) 적용 ② 생성기가 기초 데이터만 적재 ③ 기존 `MonthlyValidationJob` 등 배치 실행 ④ 정답표와 비교. 단계(1천→3천→1만2천)는 새 DB에서 각각 처음부터 만든다.
- **발표 환경 재현**: 발표 서버에서도 같은 명령으로 만든다. 실행 명령·`dataset_id`·seed·기준일·검증 결과(건수·합계)를 PR과 Runbook에 남긴다. 개발자 PC에서만 성공한 것으로 닫지 않는다.

**생성기가 넣는 것 / 넣지 않는 것**

- 넣는다: 조직(GA 1·지점 12·팀), 설계사 600(등록·활동·해촉, 활동 ≥ 500), 원수사 설계사코드, 필요하면 상품·판매버전, 계약, 상태 사건(정상·미납·실효·부활·해지), 재무 스냅샷, 원천 명세 묶음·지급 건(DRAFT→귀속→CONFIRMED, §22, `installment_no` 포함).
- 넣지 않는다: 스케줄·1,200%·차익거래·분개·대사·예외(§0 원칙 5). 정책은 기존 db/demo 정책을 쓰고, 새 정책이 필요하면 §4-1 절차를 따른다.
- GOLDEN 행(V3·V4·V45_1 계약·설계사)은 바꾸지 않는다(DAR-012 "기존 데이터 불변").

**식별과 재현성**

- 업무코드 접두어로 데이터셋을 구분한다: 설계사 `D12-A-0001~0600`, 조직 `D12-B01~B12`, 계약 일련번호 `5001~` 구간, `source_ref`/`evidence_ref`는 `SYNTHETIC:DAR012:{dataset_id}:…`. `data_origin`은 `SEED`.
- 메타데이터 파일 `{dataset_id}.json`(위치는 구현 PR에서 확정)에 `dataset_id`, 생성기 버전, seed, 기준일, 단계별 건수·금액 합계, "기준일 직전 분기 일평균 소속 설계사 600명 — 프로젝트 가정(OI-024)"을 남긴다. 전용 테이블은 만들지 않는다.
- 같은 seed·같은 단계로 두 번 만들면 건수·합계가 같아야 한다(DAR-012). 같은 DB에 같은 `dataset_id`가 있으면 실패하고 멈춘다.
- 정답표: 심은 이상(대사 차이·공통 패턴·한도 경계·상태 사건)마다 `{계약번호, 시나리오, 기대 결과 유형, 기대 금액}`을 `{dataset_id}-answers.csv`로 남긴다(FUN-075, TER-011).
- 대표 설계사와 `agent01` 연결은 §5.
- 금지: 계약자 개인정보 컬럼·값 생성(SRC-027 D-05). 4년·7년 시연 계약을 운영형 스케줄 집계에 섞기(`schedule_purpose`로 분리).

## 52. AI 문서 입력 합성 샘플 (FUN-066~069·078, TER-011)

- 샘플은 DB 시드가 아니라 **파일 + 정답 JSON**이다. 위치 제안: `src/test/resources/ai-intake/{서식}/{샘플ID}.{xlsx|pdf|png}` + 같은 이름 `.answer.json` — 프로젝트 가정.
- 모든 샘플은 합성이고 계약자 개인정보 칸이 없다(SRC-027 D-05, OI-021). 실제 회사 자료(SRC-058)의 요율을 옮기지 않는다.
- 샘플 값은 demo 시드 기준정보와 맞물려야 매칭·대사까지 이어진다: 보험사 `FGL01~FGN01`, 원수사 상품코드 `P-A-001` 등(§10), 원수사 설계사코드 `L01-77881` 등(§9), 계약번호 V3·V4 실제 값.
- 필수 샘플 세트(Must 3종):
  1. 해약환급률표: 정상(36차월) xlsx·pdf 각 1, 12차월 누락 1, 상품코드 불일치 1 → 정답은 1~36차월 값과 기대 상태.
  2. 정산데이터: R1~R8과 같은 계약으로 정상·한도 초과·해지 후 지급·원본 내 중복·모집자 불일치 각 1행 이상, 차감·환수 행 1, 1,001행·여러 시트·합계행·원/천원 단위·선행 0 코드 변형 각 1 → 정답은 행별 정규화 값과 대사 후 기대 결과 유형.
  3. 협회 등급자료: §53의 ABC·ABCD 집합.
- 정답 JSON 형식: `{sample_id, document_type, rows:[{row_no, source_location, fields:{…}, expected_row_status}], expected_after_confirm:{statement_batch_count, transaction_count, amount_total, reconciliation:[…]}}`.

## 53. 협회 등급자료 시드 (FUN-078)

- 현재 시드의 보장성 상품은 상품군이 제각각이라(HEALTH_PROTECTION은 FGL01·FGL02 2개뿐) **다른 보험사 상품 3개 이상 비교가 성립하는 상품군이 없다.**
- 동종·유사 상품군 하나(예: 생보 보장성 건강보장, 일반심사, 같은 환급 유형)에 서로 다른 보험사 상품 4개를 둔다. 추가 상품은 새 demo 마이그레이션 또는 DAR-012 생성기로 넣는다 — 팀 결정 필요.
- 순위 집합 2개: `rank_set_key='ABC'`(3개 상품 순위), `'ABCD'`(4개 상품 순위). 같은 상품·기준일에 두 집합이 공존해야 한다(제안 DDL 유일키).
- 차단 사례: 다른 회사 상품이 2개뿐인 상품군 1개, 비교 표 집합에 대응하는 순위 집합이 없는 경우 1개.
- 차이 표시 사례: 자체 계산 등급이 협회 등급과 다른 상품 1개(SRC-002 5단계 기준).
- 협회 자료는 정책버전이 아니라 기준일 버전 참조 자료다 — DRAFT·승인 절차를 적용하지 않는다(FUN-078).
- 실제 협회 파일 형식은 근거 없음 — 확인 필요. 시연은 가상 양식(§52)으로 한다.

# 부록 A. 시드 완료 체크리스트


- [ ] 실제 개인정보 0건
- [ ] 실제 회사명·증권번호 직접사용 0건
- [ ] 조직·설계사 유효기간 오류 0건
- [ ] 상품코드와 환급률표 코드 불일치 의도건 외 0건
- [ ] 1~36차월 환급률 누락 의도건 외 0건
- [ ] 모든 `policy_version`에 `source_class` 값 존재 (문자열 토큰 방식 사용 금지)
- [ ] 정책 시드가 `DRAFT` INSERT → 자식 행 → `APPROVED` → `ACTIVE` 순서를 지킴
- [ ] 모든 신규 승인·활성 정책의 `created_by`·`approved_by`가 존재하고 서로 다름
- [ ] 규제 상한과 FGC 회사 정책이 서로 다른 `policy_version`으로 분리됨
- [ ] 계약체결비용·최초 지급회차·가상 환수율이 `PROJECT_ASSUMPTION`으로 표시됨
- [ ] 신인 판정에서 직전 3년의 등록 이력과 모집 이력이 분리 저장·판정됨
- [ ] FAQ 배포안 정책의 `source_refs`에 배포 차수(SRC-024/025)가 저장됨
- [ ] REG-10 2027 시행경계와 지급단계가 분리됨
- [ ] 지급 건은 모두 DRAFT → 귀속 → CONFIRMED 순서
- [ ] 귀속합계 오류 의도건은 회귀테스트 안에서 롤백
- [ ] 생성결과 테이블 초기 0건
- [ ] Golden 기대값 파일과 시나리오 ID 일치
- [ ] DB 재생성 후 재적재 결과 동일(§43)
- [ ] DAR012 난수 seed·dataset_id 고정
- [ ] `contract_status_event.processed_at` 신규값 0건, Job별 처리이력은 별도 테이블에 존재
- [ ] `cap_check_detail` 항목코드·항목명·조건부 회차 스냅샷 누락 0건
- [ ] REG-17·18·24 메타데이터/범위 밖 기능을 규제 완료로 표시한 테스트 0건
- [ ] 등록·모집 이력이 누락되거나 구분되지 않은 신인 판정은
      `REVIEW_REQUIRED`이며 적격 여부가 확정되지 않음

# 부록 B. 참고자료

- FGC 가상 GA 운영정책서 v1.0
- FGC 요구사항명세서 v2.2.2-r19
- FGC 규제 근거·조문표 v0.2.1
- PostgreSQL Flyway `db/migration` V1~V55 + `db/demo` V3~V56 (develop `fe6d0e6c`)
- 보험사별 수수료자료 양식
- 보험사별 지급방식·환수율·시책 자료
- GA 영업규정 사례
- 보험 모집수수료 연구보고서
- 독립보험대리점 수수료 정산 관련 공개특허
- `SRC-016` GA 설계사 4년·7년 수수료 체계 및 환수 통합 분석팩 — 기사 예시 수치를 법정값으로 사용하지 않음
- `SRC-019`, `SRC-020` 회사별 지급·환수 자료 — 회사·시점별 사적 기준
- `SRC-022`, `SRC-025` 현행 보험업감독규정 통합본·FAQ 2차 배포안 — 분급 상한과 신인 판정 근거
