# FGC 화면 개편 진단과 적용 계획 (v0.1, 2026-10-29)

멘토링과 1차 발표에서 반복된 "AI가 만든 티가 난다, 통일성이 없다"는 지적을 코드 기준으로 진단하고, 디자인 시안 3종과 적용 순서를 정리한 문서입니다.
시안은 아래 디자인 캔버스에서 실제 데모 데이터로 비교할 수 있습니다.

- 시안 캔버스: https://claude.ai/artifact/4oGULYyKmeym4AQSVyVyXD (현재 화면 5장, 공통 원칙 1장, A/B/C안 각 5장)
- 진단 기준일: 2026-10-29, `develop` 기준 `25ab878` 코드와 데모 데이터(V3 시드 + 7월 월 통합검증 1회 실행)

---

## 0. 요약

1. 문제는 개별 화면이 아니라 **공통 규칙이 없다는 것**입니다. 색상값 80종, 글자 크기 35종, 둥글기 28종, 상태 배지 88곳이 화면마다 다르게 쓰입니다.
2. 그래서 어떤 시안을 고르든 **공통 원칙 7가지**(3장)를 먼저 토큰과 공통 컴포넌트에 넣습니다. 이것만으로 20여 개 화면이 한 번에 바뀝니다.
3. 시안은 세 가지입니다. **추천은 A안(신한 블루 업무형)** 입니다. 지금 구조를 유지해 남은 기간 안에 전 화면을 같은 수준으로 맞출 수 있고, 시연 환경(신한 인프라)과 결이 맞습니다.

결정이 필요한 것: 7장 참고.

---

## 1. 진단: 왜 "AI가 만든 티"가 나는가

| 항목 | 지금 | 근거 | 바꾼 뒤 |
|---|---|---|---|
| 색 | 색상값 80종(CSS 69종, 화면 코드에만 있는 인라인 색 11종). 사이드바 스틸블루, 버튼 파랑, 설계사 보라에 상태색 6종(초록, 파랑, 남색, 노랑, 주황, 빨강), 차트색 12종 | `src/styles/variables.css`, `app/shell/agent-theme.css`, `features/agent-home/model.ts` (`INSURER_COLORS` 8종, `CATEGORY_COLORS` 4종) | 강조 1, 회색 5, 위험(빨강) 1, 주의(호박색, 글자 전용) 1 |
| 배지 | 상태 배지 88곳(`StatusBadge` 직접 58곳, `CapStatus`, `ContractStatusBadge` 같은 상태 래퍼 30곳). 흰 글자에 꽉 찬 색 배경. 예/아니오, 지급/차감, 사용/사용중지, 출처 분류 같은 **속성**까지 배지 | `components/StatusBadge.tsx`, `features/base/columns.tsx`, `transaction/TransactionListPage.tsx`, `policies/PoliciesPage.tsx` | 조치가 필요한 상태만 점 + 색 글자. 속성은 일반 글자 |
| KPI 카드 | 화면 7곳이 카드 묶음으로 시작(대시보드 6, 1,200% 4, 대사 5, 예외함 유형별, 통합검증 4+6, 차익거래, 포트폴리오 4). 구현도 4가지(`KpiCard`, `.kpi-card` 직접 마크업, `.cap-react-kpi`, `.ah-kpi`) | 각 화면 파일 | 화면당 한 줄 요약 띠 1개. 0건은 흐리게, 위반만 빨강 |
| 문구 | 화면 문자열(주석 제외)에 `·` 247개, `—` 64개. 빈 값 표기가 `—`와 `-`로 섞임(대시보드는 `-`, 1,200% 화면은 `—`). 영문 머리말(`FGC WORKSPACE`, `POL-W01 · POLICY MANAGEMENT`, `BEST`), 코드값 노출(`INSURER_TO_GA`) | `grep` 결과 | 쉼표, 괄호, 슬래시, 줄바꿈. 빈 값은 `-` 하나 |
| 글자 | 크기 35종(8px부터), px와 rem 혼용. 설계사 화면 배지 9px | `features/agent-home/agent-home.css` | 6단계(12, 13, 14, 16, 20, 28), 최소 12px, Pretendard |
| 모양 | 둥글기 28종, 그림자 16종 | 전체 CSS | 둥글기 3종(8, 12, 원형), 그림자 2종(없음, 떠 있는 메뉴) |
| 움직임 | 마스코트 무한 부유(`ah-float`), 말풍선 테두리 무한 회전(`ah-border-spin`), 숫자 카운트업(`AnimatedNumber`, 숫자 10개), 카드 순차 등장(`dash-rise`) | `agent-home.css`, `dashboard.css` | 진행률 전환만 남기고 정적으로 |
| 테마 분리 | 설계사 역할만 보라 사이드바와 별도 토큰(`--brand-*`, `--ink` 등) | `app/shell/agent-theme.css`, `agent-home.css` | 같은 토큰 하나로 통일 |

### 화면에서 보이는 버그 (개편하면서 같이 고칠 것)

| 버그 | 원인 | 수정 |
|---|---|---|
| 모바일 로그인 로고가 깨진 이미지로 보임 | `LoginPage.tsx`가 `images/brand/logo-horizontal-flat.png`를 부르는데 파일이 없음 | 색 로고 파일 추가(흰 배경용) |
| 전월 실적이 0원인데 `▲ 0.0% 전월 대비` | `agent-home/model.ts`의 `deltaPercent`가 전월 0이면 0을 반환 | 전월 0이면 `null` 반환, 화면은 "전월 실적 없음" |
| `exception.css` 3곳 둥글기 미적용 | 정의되지 않은 토큰 `--radius-md` 사용 | `--radius-8`로 교체 |
| 1,200% 목록 첫머리를 "적용대상 아님" 6건이 차지, 위반 3건은 13번째 줄 | 기본 정렬 없음(서버 기본 순서) | 기본 정렬 `usagePct,desc` (5-2) |
| 1440px 포트폴리오에서 마스코트가 "계약 상태 분포" 숫자를 가림 | 고정 위치 128px 마스코트 | 작은 고정 버튼으로 교체 |
| 1440px 수수료 지급 목록의 "귀속" 열이 잘림 | 열 너비 합이 화면보다 큼 | 속성 배지를 글자로 바꾸면 폭이 줄어듦, 최소 폭 재조정 |
| `dashboard.css`에서 같은 선택자를 두 번 정의 | `.dashboard-main-grid` 25.5rem 뒤 27rem, `.risk-donut` 9rem 뒤 11rem, KPI 규칙 3겹 | 개편 시 한 번만 정의 |

---

## 2. 레퍼런스와 시안 3종

`solgit.shinhan.com`은 이 작업 환경의 네트워크 정책에 막혀 직접 분석하지 못했습니다. 대신 신한금융그룹 공식 CI의 신한블루(Pantone 2388 C, `#0046FF`)를 A안의 기준으로 삼았습니다. 화면 캡처를 주시면 둥글기, 간격, 글꼴을 그 사이트에 더 맞출 수 있습니다.

| | A안 신한 블루 업무형 | B안 토스형 미니멀 | C안 모노크롬 콘솔형 |
|---|---|---|---|
| 레퍼런스 | 신한금융그룹 CI, 은행 업무 시스템 | 토스, 토스페이먼츠 상점관리자 | Vercel, Linear, Stripe 대시보드 |
| 강조색 | `#0046FF` (Tweaks에서 FGC 스틸블루로 바꿔 볼 수 있음) | `#2070E8` (토스 블루 `#3182F6`을 흰 글자 대비 4.6:1로 보정) | 검정 `#171717`, 링크만 `#0062D1` |
| 바탕과 면 | 흰 사이드바, `#F7F8FA` 바탕, 1px 선 + 12px 둥글기 | `#F2F4F6` 바탕 위 테두리 없는 흰 면, 20px 둥글기 | 흰 바탕, 회색 선, 6~8px 둥글기 |
| 글자 | Pretendard 14px 기준 | Pretendard 15px 기준, 해요체 | Pretendard 13px + 숫자와 코드는 JetBrains Mono |
| 대시보드 | 요약 띠 + 처리할 예외 + 확정까지 남은 일 | 지금 처리할 일 목록 + 검증 결과 + 큰 진행률 | 지표 한 줄 + 심각도 순 예외 표 + 단계 체크리스트 |
| 장점 | 지금 구조 그대로, 전 화면 통일이 가장 쉬움. 발표 환경과 결이 맞음 | 시연 첫인상과 모바일이 가장 세련됨 | 표를 오래 보는 정산 담당자에게 가장 효율적 |
| 단점 | 세 안 중 가장 무난함 | 행이 높아 한 화면 건수가 줄고, 대시보드 구조와 말투까지 바뀜 | 첫인상이 차갑고 설계사 화면과 성격이 맞지 않음 |
| 바뀌는 정도 | 작음 | 중간 | 중간 |

**추천: A안.** 지적의 핵심이 "통일성"이므로, 남은 기간(최종 발표 11월 17일) 안에 20여 개 화면 전체를 같은 수준으로 맞출 수 있는 안이 가장 안전합니다. 대시보드 문구에만 B안처럼 "처리할 일" 중심 표현을 섞는 것도 가능합니다.

---

## 3. 웹 디자이너 관점: 공통 원칙 7가지

어떤 시안이든 이 순서로 먼저 적용합니다. 캔버스의 "공통 원칙" 보드에 지금과 바꾼 뒤를 나란히 그려 두었습니다.

1. **색은 강조 1, 회색, 위험만.** 정상은 기본 상태라 색을 쓰지 않습니다. 초록은 쓰지 않습니다.
2. **배지는 조치가 필요한 상태에만.** 위반, 불일치, 긴급, 신규는 점 + 글자. 속성은 일반 글자.
3. **KPI 카드 대신 한 줄 요약.** 숫자에는 분모를 붙여 맥락을 줍니다(예: `3건, 16건 검사 중`).
4. **문구에서 `·` `—` 빼기.** 예: `실제 지급 없음 — 예상 5,000원 지급 건 미발견` → `실제 지급 누락 (예상 5,000원)`, `정책 · 룰셋 관리` → `정책/룰셋 관리`, `김정산 · A-FC-001` → `김정산 (A-FC-001)`, `2026-07-01 · 1회차` → `2026년 7월 1회차`.
5. **글자 크기 6단계, 최소 12px.** 숫자는 `tabular-nums`.
6. **둥글기 3종, 그림자 2종.** 면은 그림자 대신 1px 선과 여백으로 나눕니다.
7. **움직임은 의미 있을 때만.** 진행률 전환만 남깁니다.

---

## 4. 리액트 퍼블리셔 관점: 토큰과 컴포넌트

### 4-1. 토큰 (`src/styles/variables.css`)

변수 이름은 그대로 두고 값만 바꿉니다. `tokens.js`와 Tailwind 매핑이 변수 이름을 참조하므로 화면 코드는 건드리지 않아도 됩니다. (A안 기준)

| 변수 | 지금 | A안 |
|---|---|---|
| `--color-action-primary` | `#1f54e5` | `#0046FF` |
| `--color-action-subtle` | `#eef4ff` | `#EDF2FF` |
| `--color-nav-bg-default` | `#467aa2` (스틸블루) | `#FFFFFF` (흰 사이드바, 글자 `#3D4450`) |
| `--color-text-primary` | `#0f172a` | `#1B1F24` |
| `--color-text-secondary` | `#475569` | `#59616E` |
| `--color-text-tertiary` | `#64748b` | `#6B7380` (흰 바탕 대비 4.8:1, 이보다 옅은 글자 금지) |
| `--color-border-default` | `#e2e8f0` | `#E3E6EB` |
| `--color-bg-canvas` | `#f8fafc` | `#F7F8FA` |
| 상태 24개 (`--color-status-*`) | success, info, review, warning, risk, error 각 4개 | `danger #D7261E`(대비 5.0:1), `warning #B25E00`(4.7:1) 두 묶음으로 축소. 나머지 이름은 이행 기간 동안 회색과 강조색에 연결 |
| `--font-sans` | Noto Sans KR | Pretendard (KS X 1001 서브셋 woff2 468KB, `tools/subset_fonts.py`에 추가) |
| `--radius-*` | 3, 4, 6, 8, 12, 16, full | 실사용은 8, 12, full만. `--radius-md` 정의 누락 해소 |

`tokens.test.ts`의 상태 24개 값 대조 테스트는 새 값에 맞춰 갱신합니다.

### 4-2. 공통 컴포넌트

| 컴포넌트 | 변경 | 효과 |
|---|---|---|
| `StatusBadge` | 이름과 props는 유지하고 출력만 "점 + 색 글자"로. tone 대응: `error, risk → danger`, `warning → warning`, `info, review → default`, `success, neutral → muted` | 상태 배지 88곳이 호출부 수정 없이 한 번에 차분해짐(래퍼 컴포넌트도 내부에서 `StatusBadge`를 씀) |
| `KpiCard` | `SummaryStrip` + `SummaryItem`으로 대체(label, value, unit, caption, tone, to). 0이면 비활성 회색 | 7개 화면의 카드 묶음을 같은 부품으로 |
| `Button` | primary, secondary(테두리), ghost(글자) 3종 유지. 화면당 primary 1개 규칙 | |
| `DataTable` | 머리글 12px 600 회색, 행 46px, 숫자 열 우측 정렬, 금액 단위는 열 제목으로(`금액(원)`) | |
| `FilterBar` | 한 줄 배치, 초기화는 글자 버튼, 조회만 primary | |
| `Sidebar`, `AppShell` | 그룹 접힘 대신 그룹 이름 + 평평한 메뉴, 푸터 문구 정리, 768px 미만은 상단 바 + 서랍 메뉴 + 하단 탭 | 모바일에서 아이콘 사이드바가 폭을 차지하던 문제 해결 |
| `AnimatedNumber` | 즉시 표시로 바꾸거나 사용처 제거 | |

`StatusBadge` 변경 예시:

```tsx
export type StatusTone = 'success' | 'info' | 'review' | 'warning' | 'risk' | 'error' | 'neutral'
const level: Record<StatusTone, 'danger' | 'warning' | 'default' | 'muted'> = {
  error: 'danger', risk: 'danger', warning: 'warning',
  info: 'default', review: 'default', success: 'muted', neutral: 'muted',
}
export function StatusBadge({ tone = 'neutral', children }: { tone?: StatusTone; children: ReactNode }) {
  return <span className={`status-text status-text-${level[tone]}`}>{children}</span>
}
```

```css
.status-text { display: inline-flex; align-items: center; gap: 6px; color: var(--color-text-primary); font-weight: 500; white-space: nowrap; }
.status-text-danger, .status-text-warning { font-weight: 700; }
.status-text-danger { color: var(--color-status-danger); }
.status-text-warning { color: var(--color-status-warning); }
.status-text-danger::before, .status-text-warning::before { width: 6px; height: 6px; border-radius: 999px; background: currentColor; content: ""; }
.status-text-muted { color: var(--color-text-secondary); }
```

### 4-3. 화면별 변경 목록

**KPI 카드 묶음 → 요약 띠 또는 필터 탭 (7곳)**

| 화면 | 파일 | 바꾸는 방식 |
|---|---|---|
| 업무 대시보드 | `dashboard/DashboardPage.tsx` | 요약 띠 5칸(분모 포함) + "처리할 예외 41건" 제목 숫자. 도넛은 유형별 막대로 |
| 1,200% 한도 검증 | `cap/CapListPage.tsx` | 판정 카드 4개 → 표 위 판정 탭(건수 포함). 지금도 카드가 필터 역할이라 동작 동일 |
| 예외함 | `exception/ExceptionPage.tsx` | 유형 카드 → 유형 탭 |
| 차익거래 검증 | `arbitrage/ArbitragePage.tsx` | 요약 카드 → 판정 탭 |
| 대사 실행 결과 | `reconciliation/ReconciliationPage.tsx` | `KpiCard` 5개 → 요약 띠 |
| 월 통합검증 상세 | `validation/ValidationRunDetailPage.tsx` | 결과 요약 4개, 예외 생성 결과 6개 → 요약 띠 2줄 |
| 포트폴리오 | `agent-home/AgentHomePage.tsx` | 4칸 요약 띠, 별도 보라 토큰 제거 |

**속성 배지 → 일반 글자 (예시, 호출부 수정 필요)**

| 파일 | 대상 |
|---|---|
| `base/columns.tsx` (10곳) | 사용/사용중지, 표준해약공제 예/아니오, 신인지원 대상/비대상, 지급/차감 |
| `transaction/TransactionListPage.tsx` (7곳) | 흐름(지급/차감), 귀속(`1건 · 일치` → `1건 일치`, 확인 필요만 위험색) |
| `policies/PoliciesPage.tsx`, `PolicyManagement.tsx` (21곳) | 출처 분류(규제, 회사 정책, 보험사 규칙, 프로젝트 가정), `근거 미기재` |
| `contract/badges.tsx` | `DataOriginBadge` |
| `schedule/badges.tsx` | `ClassificationBadge`, `ActiveBadge` |
| `validation/ValidationRunDetailPage.tsx` | 대상 선별의 행마다 붙은 `선정` |
| `dashboard/DashboardPage.tsx`, `exception/ExceptionPage.tsx` | 예외 유형 |

### 4-4. CSS 정리

- `dashboard.css`의 중복 정의(같은 선택자 2~3회)를 한 번으로.
- `agent-home.css`의 자체 토큰(`--brand-*`, `--ink`, `--body` 등)과 px 단위를 공통 토큰으로.
- `contract/legacy.css`(1,102줄)는 1차 화면 이식분이라 이번 범위에서는 토큰 교체 효과만 받고 구조는 유지.

---

## 5. 프론트엔드 개발자 관점: 데이터 연동과 로직

### 5-1. 대시보드 분모 (백엔드 수정 없음)

요약 띠의 "16건 검사 중" 같은 분모는 이미 있는 월 통합검증 상세 API로 얻습니다.
대시보드 응답의 `recentRuns[0].validationRunId`로 `GET /api/v1/validation-runs/{id}`를 한 번 더 조회합니다.

| 띠 항목 | 값 | 분모 |
|---|---|---|
| 1,200% 위반 | `capViolation` | `capSummary.checkedCount` (16) |
| 차익거래 검토대상 | `arbitrageCandidate` | `arbitrageSummary.checkedCount` (11) |
| 대사 불일치 | `reconMismatch` | `reconciliationSummary.resultCount` (47) |
| 원장 불균형 | `journalImbalance` | `ledgerSummary.journalCount` (70) |

실행이 없는 달은 분모 없이 표시합니다. 쿼리 옵션은 `validation/api.ts`의 상세 조회를 재사용합니다.

### 5-2. 1,200% 목록 기본 정렬 (백엔드 수정 없음)

`sort` 파라미터가 없을 때 프론트에서 `usagePct,desc`를 기본값으로 넣습니다. 서버 `SqlSort`가 `NULLS LAST`를 붙이므로 위반(108.3%)이 맨 위, 적용대상 아님(사용률 없음)이 맨 아래로 갑니다. `CAP_SORT_KEYS`에 이미 `usagePct`가 있습니다.

### 5-3. 전월 대비 계산

`agent-home/model.ts`

```ts
// 전월이 0이면 비율을 계산할 수 없으므로 null을 돌려주고, 화면은 "전월 실적 없음"을 표시한다.
export const deltaPercent = (now: number, before: number) => (before ? oneDecimal(((now - before) / before) * 100) : null)
```

`AgentHomePage.tsx`의 `Delta`와 `model.test.ts`를 함께 고칩니다.

### 5-4. 문구 변경 범위와 테스트 영향

| 위치 | 내용 | 비고 |
|---|---|---|
| 프론트 문자열 | 주석 제외 `·` 247개, `—` 64개 (주석 포함 시 390개, 105개) | 화면에 보이는 문자열만 대상, 주석은 그대로 |
| 백엔드 생성 문구 | 예외 제목: `ExceptionCaseRepository.java`의 대사 불일치 SQL(`'실제 지급 없음 — 예상 '` 등), `CapExceptionServiceImpl.java`(`1,200% 한도 초과 — 사용률`), `messages.properties`의 `—` 41곳 | 제목은 탐지 시점에 저장되므로 새 실행부터 반영. 시연 DB는 다시 시드하면 됨 |
| 테스트 | 단위 테스트에서 `·`, `—`가 든 줄 264개, e2e 55개, 백엔드 테스트 문자열 42개 | 테스트 설명문과 실제 단언을 구분해 단언만 수정 |

### 5-5. 재발 방지

`eslint.config.js`에 화면 문자열 규칙을 추가해 다시 들어오지 않게 합니다(주석과 테스트는 제외).

```js
{
  files: ['src/**/*.{ts,tsx}'],
  ignores: ['src/**/*.test.{ts,tsx}', 'src/lib/api/schema.d.ts'],
  rules: {
    'no-restricted-syntax': ['error',
      { selector: 'JSXText[value=/[·—]/]', message: '화면 문구에 · 와 — 를 쓰지 않습니다.' },
      { selector: 'Literal[value=/[·—]/]', message: '화면 문구에 · 와 — 를 쓰지 않습니다.' },
      { selector: 'TemplateElement[value.raw=/[·—]/]', message: '화면 문구에 · 와 — 를 쓰지 않습니다.' },
    ],
  },
}
```

### 5-6. 자산

- 흰 사이드바(A안)에는 색 로고가 필요합니다. 캔버스에는 흰 로고의 알파 채널로 만든 파랑, 검정 버전을 썼지만, 로고 원본(벡터)이 있으면 SVG로 받는 것이 좋습니다. 같은 파일로 모바일 로그인의 `logo-horizontal-flat.png` 누락도 해결됩니다.
- Pretendard는 OFL 무료 서체입니다. `tools/subset_fonts.py`에 Noto Sans KR과 같은 방식(KS X 1001 2,350자 + 라틴, 기호, `tnum` 기능)으로 추가하면 woff2 약 468KB입니다.

### 5-7. 모바일

지금은 1280px 미만에서 사이드바가 아이콘 열(72px)로 남아 390px 화면의 18%를 차지하고, 대시보드 KPI 카드 6개가 화면 두 개 높이를 차지합니다.
768px 미만에서는 상단 바 + 서랍 메뉴 + 하단 탭으로 바꿉니다. `e2e/app-shell.spec.ts`, `Sidebar.test.tsx`의 접힘 시나리오를 함께 갱신합니다.

### 5-8. 접근성

- 글자 대비 4.5:1 이상만 씁니다(시안 토큰에 대비값을 적어 두었습니다).
- 상태는 색만으로 구분하지 않고 점 + 글자를 함께 씁니다(화면정의서 4장 규칙 4 유지).
- 인터랙티브 시안의 판정 탭은 `aria-pressed`로 선택 상태를 알립니다.

---

## 6. 일정 (최종 발표 11월 17일 기준)

| 단계 | 기간 | 내용 | 확인 |
|---|---|---|---|
| 0. 결정 | 10/30 ~ 10/31 | 시안 선택, 토큰 확정 | 멘토 의견 |
| 1. 공통 원칙 | 11/2 ~ 11/4 | 토큰, `StatusBadge`, `SummaryStrip`, Button, DataTable, FilterBar, Pretendard, 로고, 움직임 정리 | `npm run lint && npm test -- --run && npm run build` |
| 2. 시연 동선 | 11/5 ~ 11/7 | 셸(사이드바, 탭, 모바일), 로그인, 대시보드, 1,200%, 예외함, 포트폴리오 | 1440, 1280, 390px 캡처 비교 |
| 3. 나머지 화면 | 11/9 ~ 11/12 | 속성 배지 → 글자, KPI 묶음 → 요약 띠, 프론트 문구, 백엔드 생성 문구 | 단위, e2e, 백엔드 테스트 |
| 4. 마무리 | 11/13 ~ 11/14 | 전 화면 점검, 시연 리허설 | `npm run test:e2e:demo` |

1, 3단계는 공통 부품과 화면 단위로 나뉘어 있어 2~3명이 나눠 진행할 수 있습니다.

---

## 7. 결정이 필요한 것

1. 시안 선택: A, B, C 중 하나(또는 A 기반에 B의 대시보드 문구).
2. 사이드바 색: 흰색(A안 기본) 또는 현재 스틸블루 유지(A안 Tweaks의 FGC 스틸블루).
3. 백엔드 생성 문구(예외 제목, 오류 메시지)까지 이번에 고칠지. 고치면 백엔드 테스트 수정이 함께 필요합니다.

---

## 참고

- 신한금융그룹 CI: 신한블루 Pantone 2388 C, RGB 0/70/255, `#0046FF`
- 토스 그레이 스케일과 블루(`#3182F6`)는 공개된 토스 화면 기준이며, 대비 기준에 맞춰 보정해 썼습니다.
- `solgit.shinhan.com`, 디자인 시스템 갤러리 등 일부 레퍼런스 사이트는 작업 환경의 네트워크 정책으로 열람하지 못했습니다.
