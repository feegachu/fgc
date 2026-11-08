import { request, type Page } from '@playwright/test'
import { test, expect, login, password, responseFor, sql, successful } from './fixtures'

const won = (value: number) => `${value.toLocaleString('ko-KR')}원`
const TABLES = [
  'insurance_contract',
  'contract_status_event',
  'contract_event_job',
  'contract_status_event_effect',
  'schedule_header',
  'schedule_line',
  'commission_transaction',
  'transaction_attribution',
  'payment_hold',
  'clawback_candidate',
  'clawback_case',
  'clawback_calculation',
  'audit_log',
]
// 행 수와 내용이 하나라도 바뀌면 값이 달라진다.
const database = () =>
  sql(`SELECT string_agg(snapshot, ',' ORDER BY snapshot) FROM (${TABLES.map(
    (table) => `SELECT '${table}:' || count(*) || ':' || COALESCE(md5(string_agg(t::text, '|' ORDER BY t::text)), '') AS snapshot FROM fgc.${table} t`,
  ).join(' UNION ALL ')}) tables`)

/** 어제 서울 09:00. 시드의 최신 사건보다 늦고 현재 시각보다 이르다. */
function yesterdayInSeoul() {
  const seoul = new Date(Date.now() + 9 * 3600_000 - 24 * 3600_000)
  return `${seoul.toISOString().slice(0, 10)}T09:00`
}

/** 표에 없는 전이(정상→부활)를 별도 세션으로 보낸다. 같은 계정으로 로그인하면 브라우저 세션이 중복 로그인으로 끊긴다. */
async function deniedPreview(page: Page, url: string, effectiveAt: string) {
  const origin = new URL(page.url()).origin
  const client = await request.newContext({ baseURL: origin, extraHTTPHeaders: { Origin: origin, 'X-FGC-Client': 'web' } })
  try {
    const auth = await client.post('/api/v1/auth/login', { data: { loginId: 'gaadmin', password } })
    expect(auth.ok(), await auth.text()).toBe(true)
    const token = (await auth.json()).data.accessToken
    const before = database()
    const response = await client.post(url, {
      data: { eventType: 'REVIVED', effectiveAt },
      headers: { Authorization: `Bearer ${token}` },
    })
    return { before, result: { status: response.status(), code: (await response.json()).error?.code } }
  } finally {
    await client.dispose()
  }
}

// FUN-077, IF-API-64: 실제 서버의 계산 결과를 화면과 대조하고 DB 가 바뀌지 않음을 확인한 뒤 IF-API-19 로 저장한다.
test('상태 변경 영향 미리보기는 실제 서버 결과를 보이고 DB 를 바꾸지 않으며 저장은 계약 수정으로만 한다', async ({ page }) => {
  const contractNo = 'FGC-FGL01-202607-0001'
  const id = sql(`SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = '${contractNo}'`)
  const path = `/contracts/${id}/status-events/preview`
  const effectiveAt = yesterdayInSeoul()
  await login(page, 'settle01', `/contracts/${id}?month=2026-07`)
  await page.getByRole('button', { name: '영향 미리보기' }).click()
  const dialog = page.getByRole('dialog', { name: '상태 변경 영향 미리보기' })
  await dialog.getByLabel('변경 종류').selectOption('TERMINATED')
  await dialog.getByLabel('효력일시').fill(effectiveAt)
  const before = database()

  const responded = responseFor(page, path)
  await dialog.getByRole('button', { name: '영향 미리보기' }).click()
  const preview = await successful(responded)

  expect(database(), '미리보기 전후 업무 표').toBe(before)
  expect(preview.eventType).toBe('TERMINATED')
  await expect(dialog.getByText(`변경 전 ${won(preview.heldAmount.before)}, 변경 후 ${won(preview.heldAmount.after)}`)).toBeVisible()
  // 시드 계약은 지급 정보가 있어 어느 쪽이든 서버가 준 금액과 사유를 그대로 보인다. 미계산을 0원으로 바꾸지 않는다.
  const review = preview.clawbackReview
  await expect(dialog.getByText(review.amount == null ? '미계산' : `예상 ${won(review.amount)}`, { exact: true })).toBeVisible()
  await expect(dialog.getByText(review.reason, { exact: false }).first()).toBeVisible()
  for (const paid of preview.paidAmount) {
    if (paid.paymentStage === 'GA_TO_FC' && paid.amount != null) await expect(dialog.getByText(`GA→설계사 ${won(paid.amount)}`)).toBeVisible()
  }
  if (preview.stoppedLines.length) {
    await expect(dialog.getByRole('table', { name: '멈추는 회차' })).toContainText(preview.stoppedLines[0].reason)
  } else {
    await expect(dialog.getByText('변경되는 예정 회차가 없습니다')).toBeVisible()
  }
  await page.setViewportSize({ width: 1440, height: 1400 })
  await dialog.screenshot({ path: 'e2e-artifacts/731-status-preview-real-server.png' })

  // 전이 표에 없는 사건은 화면이 선택지를 가려도 서버가 거절한다. 로그인 자체의 감사 기록은 비교에서 뺀다.
  const denied = await deniedPreview(page, `/api/v1${path}`, `${effectiveAt}:00+09:00`)
  expect(denied.result).toEqual({ status: 409, code: 'FGC-IMPL-TRANSITION_DENIED' })
  expect(database(), '거절된 미리보기 뒤에도 그대로').toBe(denied.before)

  // 이동만으로는 저장되지 않는다. 저장은 기존 계약 수정 경로로 사람이 확인한 뒤 한다.
  await dialog.getByRole('button', { name: '계약 수정으로 이동' }).click()
  await expect(page).toHaveURL(new RegExp(`/contracts/${id}/edit`))
  await expect(page.locator('select[name="contractStatus"]')).toHaveValue('TERMINATED')
  await expect(page.locator('input[name="statusEvent.effectiveAt"]')).toHaveValue(effectiveAt)
  expect(database(), '수정 화면으로 이동해도 그대로').toBe(denied.before)
  await page.locator('select[name="statusEvent.reasonCode"]').selectOption('MANUAL_STATUS_CHANGE')
  await page.locator('textarea[name="statusEvent.reason"]').fill('미리보기 뒤 계약 수정으로 저장')
  await page.getByRole('button', { name: '수정 저장' }).click()
  const saved = responseFor(page, `/contracts/${id}`, 'PUT')
  await page.getByRole('button', { name: '확인하고 저장' }).click()
  const update = await successful(saved)

  expect(update.statusEventId).toBeGreaterThan(0)
  expect(sql(`SELECT new_status FROM fgc.contract_status_event WHERE contract_status_event_id = ${update.statusEventId}`)).toBe('TERMINATED')
  expect(sql(`SELECT current_status FROM fgc.insurance_contract WHERE contract_id = ${id}`)).toBe('TERMINATED')

  // 끝 상태가 된 계약은 고를 사건이 없다. 옛 결과로 다시 저장할 길이 없다.
  await expect(page).toHaveURL(new RegExp(`/contracts/${id}`))
  await page.getByRole('button', { name: '영향 미리보기' }).click()
  await expect(dialog.getByText('끝 상태 계약이라 바꿀 수 있는 상태가 없습니다.')).toBeVisible()
  await expect(dialog.getByRole('button', { name: '영향 미리보기' })).toBeDisabled()
})
