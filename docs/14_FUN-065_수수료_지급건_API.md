# FUN-065 수수료 지급 건 등록·수정·확정 API

## 1. 개요

- 기능 ID: `FGC-FUN-065`
- 접근 역할: `SETTLEMENT`
- 응답 형식: `ApiResponse<T> { data, error, requestId }`
- 등록 상태: FUN-033 사전 한도 검증을 통과한 건만 `DRAFT`
- 확정 처리: 별도 Confirm API에서 정책 변경·동시 지급을 고려해 FUN-033 최종 재검증 수행

화면용 `@Controller`와 JSON API용 `@RestController`는 분리한다. 이 기능은 JSON API만 제공하므로
`CommissionPaymentApiController`만 구현하며 화면 Controller는 화면 구현 시 별도로 추가한다.

## 2. API 목록

| 기능 | Method | URL | 성공 상태 |
| --- | --- | --- | --- |
| 지급 건 등록 | POST | `/api/commission-payments` | 201 |
| DRAFT 수정 | PUT | `/api/commission-payments/{paymentId}` | 200 |
| 지급 건 확정 | POST | `/api/commission-payments/{paymentId}/confirm` | 200 |

## 3. 등록 API

저장 전에 FUN-033 사전검증을 수행한다. 한도 초과 건은 지급 건을 저장하지 않고 예외 건만 생성한다.
한도 이내 건은 `DRAFT`로 저장하며, 확정 시 동일 기준으로 다시 검증한다.

### Request

```json
{
  "sourceBusinessKey": "GA-2026-07-0001",
  "contractId": 3,
  "agentId": 7,
  "commissionItemCode": "BASE_COMMISSION",
  "amount": 500000,
  "attributionMonth": "2026-07",
  "scheduledPaymentDate": "2026-07-25",
  "paymentStage": "GA_TO_FC",
  "attributedContractId": 3,
  "inclusionDecisionStatus": "INCLUDED",
  "inclusionDecisionReason": "활성 룰셋에 따른 산입",
  "allocationPolicyVersion": 3,
  "allocationBasis": "DIRECT",
  "evidenceRef": "EVIDENCE-2026-07-001",
  "attributionMethod": "DIRECT",
  "note": "수기 등록"
}
```

### 주요 필드

| 필드 | 형식 | 필수 | 설명 |
| --- | --- | --- | --- |
| `sourceBusinessKey` | String(160) | 등록 시 필수 | 자연키. `GA_MANUAL_PAYMENT` 유형 내 중복 불가 |
| `contractId` | Long | 조건부 | 지급 사유가 된 계약. 비계약 선지급은 null 가능 |
| `agentId` | Long | 필수 | 수령 설계사 |
| `commissionItemCode` | String(50) | 필수 | FUN-009의 유효 항목코드 |
| `amount` | Decimal | 필수 | 0 이상, 소수 둘째 자리까지 |
| `attributionMonth` | `yyyy-MM` | 필수 | 정산월 및 귀속월 |
| `scheduledPaymentDate` | `yyyy-MM-dd` | 필수 | 지급예정일 |
| `paymentStage` | Enum | 필수 | `INSURER_TO_GA`, `GA_TO_FC` |
| `attributedContractId` | Long | 조건부 | 실제 귀속계약. 생략 시 `contractId` 사용 |
| `inclusionDecisionStatus` | Enum | 필수 | `INCLUDED`, `EXCLUDED`, `REVIEW_REQUIRED` |
| `inclusionDecisionReason` | String(1000) | 필수 | 산입 판단 근거 |
| `allocationPolicyVersion` | Long | 확정 시 필수 | 적용 정책 버전 ID |
| `allocationBasis` | String(200) | 확정 시 필수 | 배부기준 코드 또는 근거 |
| `evidenceRef` | String(500) | 조건부 | 제외·비계약 선지급 시 필수 |
| `attributionMethod` | Enum | 필수 | 아래 귀속방식 참조 |

### 귀속방식

- `DIRECT`: 계약 직접 귀속
- `SETTLEMENT_SUPPORT_MONTHLY`: 지급월 신계약에 월 단위 귀속
- `FIRST_CONTRACT_CARRY_FORWARD`: 위촉 당월 무실적 선지급분을 최초 신계약 모집월로 이월 귀속
- `APPROVED_ALLOCATION`: 승인된 배부정책 적용
- `MANUAL_REVIEW`: 수기 검토 귀속
- `NEWCOMER_NON_CONTRACT`: 위촉 당월 무실적 비계약 선지급. `EXCLUDED` 또는 `REVIEW_REQUIRED`로 DRAFT 저장

### Response

```json
{
  "data": {
    "paymentId": 101,
    "sourceBusinessKey": "GA-2026-07-0001",
    "contractId": 3,
    "agentId": 7,
    "commissionItemCode": "BASE_COMMISSION",
    "commissionItemName": "FC 기본수수료",
    "amount": 500000,
    "attributionMonth": "2026-07",
    "scheduledPaymentDate": "2026-07-25",
    "paymentStage": "GA_TO_FC",
    "status": "DRAFT",
    "attributedContractId": 3,
    "inclusionDecisionStatus": "INCLUDED",
    "inclusionDecisionReason": "활성 룰셋에 따른 산입",
    "allocationPolicyVersion": 3,
    "allocationBasis": "DIRECT",
    "evidenceRef": "EVIDENCE-2026-07-001",
    "attributionMethod": "DIRECT",
    "note": "수기 등록"
  },
  "error": null,
  "requestId": "20260805-a1b2c3"
}
```

## 4. 수정 API

- `DRAFT` 상태에서만 수정할 수 있다.
- 요청 본문은 등록 요청에서 `sourceBusinessKey`를 제외한 필드와 동일하다.
- 귀속행은 지급 건과 같은 트랜잭션에서 교체된다.
- `CONFIRMED` 또는 `CANCELLED`는 `FGC-TRAN-005`로 거절한다.

## 5. 확정 API

Confirm API는 등록·수정 시 통과한 지급 건을 다음 순서로 최종 재검증한다.

1. 지급 건 행 잠금 및 `DRAFT` 확인
2. 귀속계약·귀속금액 합계 확인
3. 배부정책 버전·배부기준·제외 증빙 확인
4. 지급 건의 정책 버전에서 `cap_rule_set` 및 `cap_rule_item` 조회
5. `CapCalculator`에서 정책 버전·환급률을 반영한 한도를 조회
6. `CapValidator`에서 기존 확정 산입액과 후보 지급액을 판정
7. `cap_check`, `cap_check_detail`에 `PRE_CONFIRM` 결과 저장
8. 실패 시 `exception_case` 생성 후 확정 차단
9. 성공 시 `commission_transaction.status = CONFIRMED`

검증 결과, 예외 생성, 상태 변경은 하나의 서비스 트랜잭션에서 처리한다. 검증 실패 시 지급 건은
`DRAFT`로 유지하면서 `exception_case`는 보존한다.

## 6. 오류 코드

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `FGC-COMMON-002` | 400 | 필수값, 참조 계약·설계사·항목·정책 오류 |
| `FGC-TRAN-001` | 409 | 자연키 중복 |
| `FGC-TRAN-002` | 422 | 확정 시 귀속계약 없음 |
| `FGC-TRAN-003` | 422 | 지급액과 귀속금액 불일치 |
| `FGC-TRAN-004` | 400 | 배부근거·정책 버전·제외 증빙 누락 |
| `FGC-TRAN-005` | 409 | DRAFT가 아닌 지급 건 수정·확정 시도 |
| `FGC-CAP-001` | 422 | 1,200% 한도 초과 |
| `FGC-CAP-002` | 422 | 분류정책 누락 또는 검토 필요 |
| `FGC-AUTH-003` | 403 | 정산담당자 역할 없음 |

## 7. DB 매핑

- 지급 사실: `commission_transaction`
- 계약·설계사 귀속 및 산입 스냅샷: `transaction_attribution`
- 자연키 제약: `uq_commission_transaction_source (source_type, source_business_key)`
- 확정 검증 결과: `cap_check`, `cap_check_detail`
- 검증 실패 예외: `exception_case`
- 배부기준·산입 근거·원계약 ID는 `allocation_basis_snapshot` JSONB에 당시 값으로 보존한다.

기존 ERD가 필요한 테이블과 제약을 모두 포함하므로 신규 Flyway 마이그레이션은 추가하지 않는다.
