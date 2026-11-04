import { expect, test } from '@playwright/test'
import type { Page, Route } from '@playwright/test'

// 브라우저 상호작용 검증용 API 응답. 실제 DB 대조는 ContractStatusPreviewIntegrationTest 와 e2e-demo 가 맡는다.
const contract = {
  contractId: 1,
  contractNo: 'CONT-PREVIEW-001',
  contractDate: '2026-01-05',
  contractStatus: 'UNPAID',
  insurerId: 1,
  insurerName: '시연 보험사',
  productOfferingId: 7,
  productName: '시연 상품',
  offeringVersion: 'v1',
  agentId: 3,
  agentName: '시연 설계사',
  organizationId: 4,
  organizationName: '시연 조직',
  paymentCycleCode: 'MONTHLY',
  premiumPerCycleAmount: 100000,
  firstPremiumAmount: 100000,
  monthlyEquivalentFirstPremium: 100000,
  paymentTermMonths: 120,
  updatedAt: '2026-01-20T00:00:00.123456Z',
  allowedNextStatuses: ['ACTIVE', 'LAPSED', 'TERMINATED'],
  statusReasonCodes: ['MANUAL_STATUS_CHANGE'],
}
const line = (id: number, previousState: string, nextState: string, reasonCode: string, reason: string, extra = {}) => ({
  scheduleLineId: id,
  paymentStage: 'GA_TO_FC',
  commissionItemName: '유지관리수수료',
  installmentNo: id,
  dueDate: '2026-02-25',
  nextDueDate: null,
  amount: 120000,
  previousState,
  nextState,
  reasonCode,
  reason,
  ...extra,
})
const preview = {
  contractId: 1,
  currentStatus: 'UNPAID',
  eventType: 'LAPSED',
  effectiveAt: '2026-01-15T09:00:00+09:00',
  stoppedLines: [
    line(3, 'HOLD', 'CANCELLED', 'STATUS_INACTIVE', '지급예정일 2026-02-25 시점 계약 상태(실효)에 따라 지급하지 않습니다.'),
    line(2, 'HOLD', 'CANCELLED', 'UNPAID_ENDED', '지급예정일 2026-01-25 시점 미납이었고 이후 상태(실효)에 따라 지급하지 않습니다.', {
      dueDate: '2026-01-25',
    }),
  ],
  resumedLines: [],
  excludedLines: [
    line(1, 'CONFIRMED', 'EXCLUDED', 'ALREADY_CONFIRMED', '이미 확정된 회차라 바꾸지 않습니다(이력 보존).', { dueDate: '2026-01-10' }),
  ],
  payments: [
    {
      commissionTransactionId: 91,
      amount: 300000,
      previousState: 'DRAFT',
      nextState: 'HELD',
      reasonCode: 'STATUS_INACTIVE',
      reason: '귀속일 2026-01-20 시점 계약 상태(실효)에 따라 지급하지 않습니다.',
    },
  ],
  heldAmount: { before: 100000, after: 400000 },
  paidAmount: [
    { paymentStage: 'INSURER_TO_GA', amount: 700000 },
    { paymentStage: 'GA_TO_FC', amount: 500000 },
  ],
  clawbackReview: {
    candidate: true,
    amount: 250000,
    reason: '환수 정책으로 계산한 예상액입니다. 확정액이 아니며, 저장 뒤 환수 화면에서 정책을 확인해 계산합니다.',
    reasonCodes: [],
    elapsedMonths: 0,
    policyVersionId: 77,
    policyCode: 'CLAW-GA-VIRTUAL-2026',
    policyVersionNo: 1,
    lines: [
      { transactionAttributionId: 501, commissionItemName: '기본수수료', installmentNo: 1, basisAmount: 500000, appliedRatePct: 50, clawbackAmount: 250000 },
    ],
  },
  pendingFollowUps: 0,
  snapshotAt: '2026-01-20T10:30:15+09:00',
}
const noBasis = {
  ...preview,
  eventType: 'TERMINATED',
  stoppedLines: [],
  excludedLines: [],
  payments: [],
  heldAmount: { before: 0, after: 0 },
  paidAmount: [
    { paymentStage: 'INSURER_TO_GA', amount: 0 },
    { paymentStage: 'GA_TO_FC', amount: null },
  ],
  clawbackReview: {
    candidate: true,
    amount: null,
    reason: '근거 없음(회사 약정 필요). 적용할 환수 정책이 없어 계산하지 않았습니다.',
    reasonCodes: ['POLICY_MISSING'],
    elapsedMonths: 0,
    policyVersionId: null,
    policyCode: null,
    policyVersionNo: null,
    lines: [],
  },
}
const envelope = (data: unknown) => ({ data, error: null, requestId: 'status-preview-browser' })
const failure = (status: number, code: string, message: string, extra: object = {}) => ({
  status,
  json: { data: null, error: { code, message, ...extra }, requestId: 'status-preview-failure' },
})

type PreviewReply = (route: Route, attempt: number) => Promise<void> | void

/** 미리보기 응답을 시나리오마다 바꾸고 쓰기 요청을 기록한다. */
async function fixture(page: Page, reply: PreviewReply, { roleCode = 'SETTLEMENT', canProcess = true } = {}) {
  const writes: string[] = []
  const previews: unknown[] = []
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/auth/refresh'))
      return route.fulfill({ json: envelope({ accessToken: 'status-preview-fixture', expiresIn: 1800 }) })
    if (path.endsWith('/auth/me'))
      return route.fulfill({
        json: envelope({ loginId: 'tester', userName: '시연 담당자', roleCode, canProcess, demoMonth: '2026-07' }),
      })
    if (path.endsWith('/status-events/preview')) {
      previews.push(request.postDataJSON())
      return reply(route, previews.length)
    }
    if (request.method() !== 'GET') {
      writes.push(`${request.method()} ${path}`)
      return route.abort()
    }
    if (path.endsWith('/contracts/1')) return route.fulfill({ json: envelope(contract) })
    if (path.endsWith('/base/insurers'))
      return route.fulfill({ json: envelope({ content: [{ insurerId: 1, insurerName: '시연 보험사', activeYn: true }] }) })
    if (path.endsWith('/base/products'))
      return route.fulfill({
        json: envelope({
          content: [{ productOfferingId: 7, productName: '시연 상품', offeringVersion: 'v1', paymentTermMonths: 120, standardDeduction80Yn: false }],
        }),
      })
    if (path.endsWith('/base/agents'))
      return route.fulfill({
        json: envelope({
          content: [{ agentId: 3, agentName: '시연 설계사', agentCode: 'FC3', agentStatus: 'ACTIVE', rankCode: 'FC', activeYn: true, organizationId: 4, organizationName: '시연 조직' }],
        }),
      })
    return route.fulfill({ json: envelope([]) })
  })
  return { writes, previews }
}

async function openModal(page: Page, query = '') {
  await page.goto(`contracts/1${query}`)
  await page.getByRole('button', { name: '영향 미리보기' }).click()
  return page.getByRole('dialog', { name: '상태 사건 영향 미리보기' })
}
async function fill(dialog: ReturnType<Page['getByRole']>, eventType = 'LAPSED', effectiveAt = '2026-01-15T09:00') {
  await dialog.getByLabel('사건 종류').selectOption(eventType)
  await dialog.getByLabel('효력일시').fill(effectiveAt)
}
const submit = (dialog: ReturnType<Page['getByRole']>) => dialog.getByRole('button', { name: '영향 미리보기' })

test('실효 미리보기는 회차, 보류, 기지급, 환수 근거를 보이고 입력이 바뀌면 낡음으로 표시하며 저장하지 않는다', async ({ page }) => {
  const { writes, previews } = await fixture(page, (route) => route.fulfill({ json: envelope(preview) }))
  const dialog = await openModal(page, '?tab=basic')
  await fill(dialog)

  await submit(dialog).click()

  await expect(dialog.getByRole('table', { name: '멈추는 회차' })).toContainText('보류 → 취소')
  await expect(dialog.getByRole('table', { name: '바꾸지 않는 회차' })).toContainText('이미 확정된 회차')
  await expect(dialog.getByRole('table', { name: '미확정 지급 건 영향' })).toContainText('작성중 → 보류')
  await expect(dialog.getByText('변경 전 100,000원, 변경 후 400,000원')).toBeVisible()
  await expect(dialog.getByText('예상 250,000원')).toBeVisible()
  await expect(dialog.getByText(/스냅샷 시각 2026-01-20 10:30:15 기준/)).toBeVisible()
  expect(previews).toEqual([{ eventType: 'LAPSED', effectiveAt: '2026-01-15T09:00:00+09:00' }])
  await page.setViewportSize({ width: 1440, height: 1100 })
  await dialog.screenshot({ path: 'e2e-artifacts/731-status-preview-result.png' })

  await dialog.getByLabel('효력일시').fill('2026-01-16T09:00')
  await expect(dialog.getByText('낡음')).toBeVisible()
  await dialog.screenshot({ path: 'e2e-artifacts/731-status-preview-stale.png' })

  await dialog.getByRole('button', { name: '닫기', exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(page).toHaveURL(/\?tab=basic/)
  await page.screenshot({ path: 'e2e-artifacts/731-status-preview-entry.png' })
  expect(writes).toEqual([])
})

test('회사 약정이 없으면 미계산과 근거 없음이고 자료 없음과 실제 0원을 구분한다', async ({ page }) => {
  const { writes } = await fixture(page, (route) => route.fulfill({ json: envelope(noBasis) }))
  const dialog = await openModal(page)
  await fill(dialog, 'TERMINATED')

  await submit(dialog).click()

  await expect(dialog.getByText('미계산', { exact: true })).toBeVisible()
  await expect(dialog.getByText(/근거 없음\(회사 약정 필요\)/)).toBeVisible()
  await expect(dialog.getByText('유효한 환수 정책 없음')).toBeVisible()
  await expect(dialog.getByText('변경 전 0원, 변경 후 0원')).toBeVisible()
  await expect(dialog.getByText('GA→설계사 자료 없음')).toBeVisible()
  await expect(dialog.getByText('변경되는 예정 회차가 없습니다')).toBeVisible()
  await dialog.screenshot({ path: 'e2e-artifacts/731-status-preview-no-basis.png' })
  expect(writes).toEqual([])
})

test('전이 불가, 일자 범위, 근거 부족, 권한 없음, 서버 실패를 서로 다르게 보이고 입력을 지우지 않는다', async ({ page }) => {
  const replies = [
    failure(409, 'FGC-IMPL-TRANSITION_DENIED', '저장 불가', { params: { from: '미납', to: '해지' } }),
    failure(422, 'FGC-IMPL-EVENT_ORDER', '저장 불가', { field: 'effectiveAt' }),
    failure(422, 'FGC-IMPL-POLICY_MISSING', '근거 부족', { field: 'contractDate' }),
    failure(403, 'FGC-AUTH-003', '권한 없음'),
    failure(500, 'FGC-COMMON-500', '내부 오류'),
  ]
  const { writes } = await fixture(page, (route, attempt) =>
    route.fulfill(attempt <= replies.length ? replies[attempt - 1] : { json: envelope(preview) }),
  )
  const dialog = await openModal(page)
  await fill(dialog, 'TERMINATED')

  await submit(dialog).click()
  await expect(dialog.getByRole('alert')).toContainText('지금 미납에서 해지(으)로 바꿀 수 없습니다')
  await expect(dialog.getByRole('button', { name: '다시 조회' })).toBeVisible()
  await dialog.screenshot({ path: 'e2e-artifacts/731-status-preview-conflict.png' })
  await dialog.getByRole('button', { name: '다시 조회' }).click()
  await expect(dialog.getByRole('alert')).toBeHidden()

  await submit(dialog).click()
  await expect(dialog.getByText('효력일시는 최신 사건의 효력일시 이후이고 현재 시각을 넘을 수 없습니다.')).toBeVisible()
  await expect(dialog.getByLabel('효력일시')).toBeFocused()

  await submit(dialog).click()
  await expect(dialog.getByRole('alert')).toContainText('상태별 지급조건 정책을 확인할 수 없어 계산하지 않았습니다')

  await submit(dialog).click()
  await expect(dialog.getByRole('alert')).toContainText('영향 미리보기 권한이 없습니다.')

  await submit(dialog).click()
  await expect(dialog.getByRole('alert')).toContainText('요청번호 status-preview-failure')
  await dialog.getByRole('button', { name: '다시 시도' }).click()
  await expect(dialog.getByRole('table', { name: '멈추는 회차' })).toBeVisible()
  await expect(dialog.getByLabel('사건 종류')).toHaveValue('TERMINATED')
  await expect(dialog.getByLabel('효력일시')).toHaveValue('2026-01-15T09:00')
  expect(writes).toEqual([])
})

test('401 은 기존 인증 복구로 다시 보내고, 응답이 유실되면 실패를 안내하고 재시도로 복구한다', async ({ page }) => {
  let attempts = 0
  const { writes } = await fixture(page, async (route) => {
    attempts += 1
    if (attempts === 1) return route.fulfill(failure(401, 'FGC-AUTH-001', '인증 필요'))
    if (attempts === 3) return route.abort('connectionreset')
    return route.fulfill({ json: envelope(preview) })
  })
  const dialog = await openModal(page)
  await fill(dialog)

  await submit(dialog).click()
  await expect(dialog.getByRole('table', { name: '멈추는 회차' })).toBeVisible()
  expect(attempts).toBe(2)

  await submit(dialog).click()
  await expect(dialog.getByRole('alert')).toContainText('저장된 것은 없습니다')
  await expect(dialog.getByRole('table', { name: '멈추는 회차' })).toBeHidden()
  await dialog.getByRole('button', { name: '다시 시도' }).click()
  await expect(dialog.getByRole('table', { name: '멈추는 회차' })).toBeVisible()
  expect(writes).toEqual([])
})

test('처리 중 중복 클릭을 잠그고 입력이 바뀐 뒤 도착한 늦은 응답은 결과로 쓰지 않는다', async ({ page }) => {
  let release: () => void = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  const { previews } = await fixture(page, async (route, attempt) => {
    if (attempt === 1) await held
    await route.fulfill({ json: envelope(preview) }).catch(() => {})
  })
  const dialog = await openModal(page)
  await fill(dialog)

  await submit(dialog).click()
  await expect(submit(dialog)).toBeDisabled()
  await expect(dialog.getByRole('status')).toContainText('영향을 계산하는 중입니다.')
  await dialog.getByLabel('효력일시').fill('2026-01-16T09:00')
  release()
  await expect(dialog.getByRole('table', { name: '멈추는 회차' })).toBeHidden()
  expect(previews).toHaveLength(1)

  await submit(dialog).click()
  await expect(dialog.getByRole('table', { name: '멈추는 회차' })).toBeVisible()
  await expect(dialog.getByText('낡음')).toBeHidden()
})

test('계약 수정으로 이동하면 상태와 효력일시를 초안으로 채우고 저장은 사람이 누를 때만 한다', async ({ page }) => {
  const { writes } = await fixture(page, (route) => route.fulfill({ json: envelope(preview) }))
  const dialog = await openModal(page)
  await fill(dialog)
  await submit(dialog).click()
  await expect(dialog.getByRole('table', { name: '멈추는 회차' })).toBeVisible()

  await dialog.getByRole('button', { name: '계약 수정으로 이동' }).click()

  await expect(page).toHaveURL(/\/contracts\/1\/edit/)
  await expect(page.getByRole('heading', { name: '보험계약 수정' })).toBeVisible()
  await expect(page.locator('select[name="contractStatus"]')).toHaveValue('LAPSED')
  await expect(page.locator('input[name="statusEvent.effectiveAt"]')).toHaveValue('2026-01-15T09:00')
  await expect(page.locator('textarea[name="statusEvent.reason"]')).toHaveValue('')
  expect(writes).toEqual([])
})

test('직접 URL 로 와도 COMPLIANCE 에게는 버튼이 없고, GA_ADMIN 은 미리보기만 하고 수정 이동은 없다', async ({ page }) => {
  await fixture(page, (route) => route.fulfill({ json: envelope(preview) }), { roleCode: 'COMPLIANCE', canProcess: false })
  await page.goto('contracts/1')
  await expect(page.getByRole('heading', { name: 'CONT-PREVIEW-001' })).toBeVisible()
  await expect(page.getByRole('button', { name: '영향 미리보기' })).toHaveCount(0)

  await page.unrouteAll()
  await fixture(page, (route) => route.fulfill({ json: envelope(preview) }), { roleCode: 'GA_ADMIN', canProcess: false })
  const dialog = await openModal(page)
  await fill(dialog)
  await submit(dialog).click()
  await expect(dialog.getByRole('table', { name: '멈추는 회차' })).toBeVisible()
  await expect(dialog.getByRole('button', { name: '계약 수정으로 이동' })).toHaveCount(0)
  await expect(dialog.getByText('계약 수정은 정산담당자와 시스템관리자만 할 수 있습니다.')).toBeVisible()
})
