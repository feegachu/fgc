import { test, expect, login, responseFor, successful, sql } from './fixtures'
import type { Page } from '@playwright/test'

const route = '/policies?asOf=2000-07-01'

async function newClawback(page: Page, code: string) {
  await page.getByRole('button', { name: /정책 등록/ }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('정책코드').fill(code)
  await dialog.getByLabel('정책명').fill('환수 정책 실제 API 검증')
  await dialog.getByLabel('정책 유형').selectOption('CLAWBACK')
  await dialog.getByLabel('출처 분류').selectOption('GA_POLICY')
  await dialog.getByLabel('적용 시작일').fill('2000-01-01')
  await dialog.getByLabel('적용 종료일').fill('2000-12-31')
  await dialog.getByRole('button', { name: /환수 규칙/ }).click()
  await dialog.getByRole('button', { name: /환수 규칙 추가/ }).click()
  await dialog.getByLabel('경과월 시작').fill('0')
  await dialog.getByLabel('경과월 끝').fill('11')
  await dialog.getByLabel('기준금액').selectOption('PAID_ATTRIBUTED_AMOUNT')
  await dialog.getByLabel('환수율 (%)').fill('33.333333')
  await dialog.getByRole('button', { name: /근거 자료/ }).click()
  await dialog.getByLabel('규정 근거 코드').fill('REG-13,REG-14')
}

async function action(page: Page, label: string) {
  await page.getByRole('button', { name: label, exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('작업 사유').fill(`#722 실제 API ${label} 검증`)
  const response = responseFor(page, `/policies/${new URL(page.url()).searchParams.get('policyVersionId')}/actions`)
  await dialog.getByRole('button', { name: `${label} 확인`, exact: true }).click()
  return successful(response)
}

test('POL-W01 CT-P01 환수 등록·타 사용자 승인·활성화·복제와 원본 보존', async ({ page }, info) => {
  await login(page, 'settle01', route)
  await newClawback(page, 'E2E-722-CLAW')
  const create = responseFor(page, '/policies')
  await page.getByRole('button', { name: '초안 저장' }).click()
  const created = await successful(create)
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('table', { name: '환수 규칙', exact: true })).toContainText('33.3%')
  await page.screenshot({ path: info.outputPath('01-real-api-draft.png'), fullPage: true })
  await action(page, '제출')
  const id = created.policyVersionId
  await login(page, 'gaadmin', `${route}&tab=management&policyVersionId=${id}`)
  await expect(page.getByRole('button', { name: '승인', exact: true })).toBeEnabled()
  await action(page, '승인')
  await expect(page.getByRole('button', { name: '활성화', exact: true })).toBeEnabled()
  await page.screenshot({ path: info.outputPath('02-real-api-approved.png'), fullPage: true })
  await action(page, '활성화')
  await expect(page.getByRole('button', { name: '초안 수정', exact: true })).toHaveCount(0)
  await page.getByRole('tab', { name: '승인 이력', exact: true }).click()
  await expect(page.locator('.policy-timeline')).toContainText('gaadmin')
  await expect(page.locator('.policy-timeline')).toContainText('활성화')
  await page.screenshot({ path: info.outputPath('03-real-api-history.png'), fullPage: true })
  await page.getByRole('button', { name: '새 버전 작성', exact: true }).click()
  await page.getByLabel('새 버전 적용 시작일').fill('2099-01-01')
  await page.getByLabel('새 버전 적용 종료일').fill('2099-12-31')
  await page.getByLabel('작업 사유').fill('새 버전의 환수율만 변경')
  const copy = responseFor(page, `/policies/${id}/versions`)
  await page.getByRole('button', { name: '새 초안 만들기', exact: true }).click()
  const cloned = await successful(copy)
  await expect(page).toHaveURL(new RegExp(`policyVersionId=${cloned.policyVersionId}`))
  await expect(page.getByLabel('적용 기준일')).toHaveValue('2000-07-01')
  await page.getByRole('button', { name: '초안 수정', exact: true }).click()
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /환수 규칙/ })
    .click()
  await expect(page.getByLabel('환수율 (%)')).toHaveValue('33.333333')
  await page.getByLabel('환수율 (%)').fill('0')
  const update = responseFor(page, `/policies/${cloned.policyVersionId}`, 'PUT')
  await page.getByRole('button', { name: '초안 저장', exact: true }).click()
  await successful(update)
  expect(sql(`SELECT clawback_rate_pct FROM fgc.clawback_rule WHERE policy_version_id=${id}`)).toBe('33.333333')
  expect(sql(`SELECT clawback_rate_pct FROM fgc.clawback_rule WHERE policy_version_id=${cloned.policyVersionId}`)).toBe(
    '0.000000',
  )
  await page.screenshot({ path: info.outputPath('04-real-api-new-draft-zero.png'), fullPage: true })
})

test('POL-W01 CT-P02 실제 422 필드 이동·입력 보존·중복 클릭 잠금·편집 취소', async ({ page }, info) => {
  await login(page, 'settle01', route)
  await newClawback(page, 'E2E-722-ERROR')
  await page
    .getByRole('dialog')
    .getByRole('button', { name: /환수 규칙/ })
    .click()
  await page.getByRole('button', { name: /환수 규칙 추가/ }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('경과월 시작').nth(1).fill('11')
  await dialog.getByLabel('경과월 끝').nth(1).fill('23')
  await dialog.getByLabel('기준금액').nth(1).selectOption('PAID_ATTRIBUTED_AMOUNT')
  await dialog.getByLabel('환수율 (%)').nth(1).fill('50')
  const response = responseFor(page, '/policies')
  await dialog.getByRole('button', { name: '초안 저장' }).click()
  expect((await response).status()).toBe(422)
  await expect(dialog.getByLabel('경과월 시작').nth(1)).toBeFocused()
  await expect(dialog.getByLabel('환수율 (%)').first()).toHaveValue('33.333333')
  await page.screenshot({ path: info.outputPath('05-real-api-422.png'), fullPage: true })
  await dialog.getByLabel('경과월 시작').nth(1).fill('12')
  let writes = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/policies') writes++
  })
  let release!: () => void
  const pending = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route('**/api/v1/policies', async (route) => {
    await pending
    await route.continue()
  })
  const create = responseFor(page, '/policies')
  await dialog.getByRole('button', { name: '초안 저장' }).click()
  await expect(dialog.getByRole('button', { name: /초안 저장/ })).toBeDisabled()
  await expect(dialog.getByRole('button', { name: '취소', exact: true })).toBeDisabled()
  release()
  await successful(create)
  expect(writes).toBe(1)
  await page.getByRole('button', { name: '초안 수정', exact: true }).click()
  await page.getByLabel('정책명').fill('보존할 입력')
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await page.getByRole('button', { name: '계속 편집', exact: true }).click()
  await expect(page.getByLabel('정책명')).toHaveValue('보존할 입력')
  await page.getByRole('button', { name: '취소', exact: true }).click()
  await page.getByRole('button', { name: '편집 취소', exact: true }).click()
  await expect(page.getByRole('button', { name: '초안 수정', exact: true })).toBeEnabled()
})

test('POL-W01 조회 로딩·0건·403·서버 실패·재시도 표시 (응답 장애 주입)', async ({ page }, info) => {
  await login(page, 'audit01', route)
  await expect(page.getByRole('button', { name: /정책 등록/ })).toHaveCount(0)
  await expect(page.getByRole('table', { name: '정책 버전', exact: true })).toBeVisible()
  let releaseLate!: () => void
  let lateHandled!: () => void
  const held = new Promise<void>((resolve) => {
    releaseLate = resolve
  })
  const handled = new Promise<void>((resolve) => {
    lateHandled = resolve
  })
  await page.route('**/api/v1/policies?*', async (route) => {
    if (new URL(route.request().url()).searchParams.get('asOf') !== '1900-01-01') return route.continue()
    await held
    try {
      await route.fulfill({
        json: {
          data: [{ policyVersionId: 999999, policyCode: 'STALE-LATE-722' }],
          error: null,
          requestId: 'qa-722-late',
        },
      })
    } finally {
      lateHandled()
    }
  })
  await page.goto('policies?asOf=1900-01-01')
  await expect(page.getByText('불러오는 중…', { exact: true })).toBeVisible()
  await page.getByLabel('적용 기준일').fill('2000-07-01')
  await expect(page.getByRole('table', { name: '정책 버전', exact: true })).toBeVisible()
  releaseLate()
  await handled
  await expect(page.getByText('STALE-LATE-722')).not.toBeVisible()
  await page.unroute('**/api/v1/policies?*')
  for (const [status, data, error] of [
    [200, [], null],
    [403, null, { code: 'FGC-AUTH-003', message: '접근 권한이 없습니다.' }],
    [500, null, { code: 'FGC-COMMON-500', message: '서버 오류' }],
  ] as const) {
    await page.route('**/api/v1/policies?*', (route) =>
      route.fulfill({
        status,
        json: { data, error, requestId: 'qa-722-fault-injection' },
      }),
    )
    await page.reload()
    if (status === 200) await expect(page.getByText(/기준일에 적용되는 정책 버전이 없습니다/)).toBeVisible()
    else {
      await expect(page.getByRole('alert').first()).toBeVisible()
      await expect(page.getByText(/기준일에 적용되는 정책 버전이 없습니다/)).not.toBeVisible()
      await expect(page.getByRole('button', { name: '다시 조회', exact: true })).toBeEnabled()
    }
    await page.screenshot({ path: info.outputPath(`06-query-${status}.png`), fullPage: true })
    await page.unroute('**/api/v1/policies?*')
  }
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(page.getByRole('table', { name: '정책 버전', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '초기화', exact: true }).click()
  await expect(page.getByLabel('적용 기준일')).not.toHaveValue('2000-07-01')
  await expect(page.getByRole('table', { name: '정책 버전', exact: true })).toBeVisible()
})

test('POL-W01 실제 409 입력 보존·최신 조회와 응답 유실 재시도·401 복구', async ({ page }, info) => {
  await login(page, 'settle01', route)
  await newClawback(page, 'E2E-722-RETRY')
  const keys: (string | undefined)[] = []
  let loseResponse = true
  await page.route('**/api/v1/policies', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    keys.push(route.request().headers()['idempotency-key'])
    const response = await route.fetch()
    if (loseResponse) {
      loseResponse = false
      await route.abort('failed')
    } else await route.fulfill({ response })
  })
  await page.getByRole('button', { name: '초안 저장', exact: true }).click()
  await expect(page.getByRole('dialog').getByRole('alert')).toBeVisible()
  await page.getByRole('button', { name: '초안 저장', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBeTruthy()
  expect(keys[1]).toBe(keys[0])
  expect(sql("SELECT count(*) FROM fgc.policy_version WHERE policy_code='E2E-722-RETRY'")).toBe('1')
  await page.getByRole('button', { name: '초안 수정', exact: true }).click()
  await page.getByLabel('정책명').fill('보존할 수정 내용')
  const id = Number(new URL(page.url()).searchParams.get('policyVersionId'))
  expect(Number.isSafeInteger(id) && id > 0).toBe(true)
  sql(`UPDATE fgc.policy_version SET updated_at=clock_timestamp() WHERE policy_version_id=${id}`)
  const conflict = responseFor(page, `/policies/${id}`, 'PUT')
  await page.getByRole('button', { name: '초안 저장', exact: true }).click()
  expect((await conflict).status()).toBe(409)
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(page.getByText(/최신 정책:/)).toBeVisible()
  await expect(page.getByLabel('정책명')).toHaveValue('보존할 수정 내용')
  await page.screenshot({ path: info.outputPath('07-real-api-409-preserved.png'), fullPage: true })
  sql(
    "UPDATE fgc.auth_refresh_token SET revoked_at=clock_timestamp(), revoked_reason='LOGOUT' WHERE revoked_at IS NULL AND user_id=(SELECT user_id FROM fgc.app_user WHERE login_id='settle01')",
  )
  await page.reload()
  await expect(page).toHaveURL(/\/login/)
  await page.screenshot({ path: info.outputPath('08-real-api-401.png'), fullPage: true })
})

test('POL-W01 CT-P02 실제 제출 근거 누락은 초안의 근거 필드로 이동', async ({ page }, info) => {
  await login(page, 'settle01', route)
  await newClawback(page, 'E2E-722-SOURCE')
  await page.getByRole('dialog').getByLabel('규정 근거 코드').fill('')
  const create = responseFor(page, '/policies')
  await page.getByRole('button', { name: '초안 저장', exact: true }).click()
  const created = await successful(create)
  await page.getByRole('button', { name: '제출', exact: true }).click()
  await page.getByLabel('작업 사유').fill('누락된 근거 검증')
  const response = responseFor(page, `/policies/${created.policyVersionId}/actions`)
  await page.getByRole('button', { name: '제출 확인', exact: true }).click()
  expect((await response).status()).toBe(422)
  await expect(page.getByLabel('출처 근거 코드')).toBeFocused()
  await page.screenshot({ path: info.outputPath('09-real-api-missing-source.png'), fullPage: true })
})
