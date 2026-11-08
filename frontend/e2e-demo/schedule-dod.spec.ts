import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { request, type Page } from '@playwright/test'
import { test, expect, login, password, responseFor, successful, sql } from './fixtures'

// Only the labeled, disposable E2E DB is used. Fixtures define approved policy data;
// the actual policy/schedule APIs perform approval, calculation and versioning.
async function actor(account: string) {
  const client = await request.newContext({ baseURL: 'http://127.0.0.1:15173', extraHTTPHeaders: { Origin: 'http://127.0.0.1:15173', 'X-FGC-Client': 'web' } })
  const response = await client.post('/api/v1/auth/login', { data: { loginId: account, password } })
  expect(response.ok(), await response.text()).toBe(true)
  const token = (await response.json()).data.accessToken
  return {
    client,
    async call(path: string, data?: unknown, expected = 200) {
      const response = await client.fetch(`/api/v1${path}`, { method: data === undefined ? 'GET' : 'POST', data, headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': randomUUID() } })
      expect(response.status(), await response.text()).toBe(expected)
      return (await response.json()).data
    },
    async raw(path: string, method = 'GET', data?: unknown) {
      return client.fetch(`/api/v1${path}`, { method, data, headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': randomUUID() } })
    },
  }
}

async function preview(seven: boolean, short = false) {
  const years = seven ? 7 : 4
  const regime = seven ? 'SEVEN_YEAR_2029' : 'FOUR_YEAR_2027'
  const from = seven ? '2029-01-01' : '2027-01-01'
  const date = seven ? '2029-03-02' : '2027-03-02'
  const code = `DOD-${years}-${short ? '12' : 'FULL'}`
  const ids = JSON.parse(sql(`WITH i AS (
    INSERT INTO fgc.insurer(insurer_code,insurer_name,insurer_type) VALUES ('${code}','UI DoD 시험 보험회사','LIFE') RETURNING insurer_id
  ), p AS (
    INSERT INTO fgc.product(insurer_id,insurer_product_code,standard_product_code,product_name,product_group_code)
    SELECT insurer_id,'${code}','${code}','${years}년 DoD 시험 상품','LIFE' FROM i RETURNING product_id,insurer_id
  ), o AS (
    INSERT INTO fgc.product_offering(product_id,offering_version,sales_start_date,basic_document_version,basic_document_date,channel_code,fee_regime_code)
    SELECT product_id,'DOD','${from}','${code}','${from}','FACE_TO_FACE','${regime}' FROM p RETURNING product_offering_id
  ), c AS (
    INSERT INTO fgc.insurance_contract(insurer_id,product_offering_id,contract_no,contract_date,agent_id,organization_id,premium_per_cycle_amount,first_premium_amount,monthly_equivalent_first_premium,payment_term_months)
    SELECT p.insurer_id,o.product_offering_id,'${code}','${date}',c.agent_id,c.organization_id,100000,100000,100000,240
    FROM p,o,(SELECT agent_id,organization_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1) c RETURNING contract_id
  ), item AS (
    INSERT INTO fgc.commission_item(item_code,item_name,cashflow_type,item_category,effective_from)
    VALUES ('${code}-BASE','유지 예정 수수료','PAYMENT','SALES','2026-01-01'),('${code}-LONG','장기유지 예정 수수료','PAYMENT','SALES','2026-01-01') RETURNING commission_item_id,item_code
  ) SELECT json_build_object('insurer',(SELECT insurer_id FROM i),'offering',(SELECT product_offering_id FROM o),'contract',(SELECT contract_id FROM c),'item',(SELECT commission_item_id FROM item WHERE item_code='${code}-BASE'),'longItem',(SELECT commission_item_id FROM item WHERE item_code='${code}-LONG'))`))
  const rule = { paymentStage: 'GA_TO_FC', insurerId: ids.insurer, productOfferingId: ids.offering, commissionItemId: ids.item, agentRankCode: 'FC', feeComponentType: 'MAINTENANCE', installmentFrom: 1, installmentTo: short ? 12 : seven ? 84 : 48, calculationType: short ? 'FIXED' : 'RATE', basisCode: 'MONTHLY_EQUIVALENT_FIRST_PREMIUM', ratePct: short ? null : seven ? '24.000000' : '45.000000', fixedAmount: short ? '12000' : null, equalMonthlyYn: true, paymentConditionCode: null, priorityNo: 100 }
  const writer = await actor('settle01')
  const approver = await actor('gaadmin')
  try {
    const created = await writer.call('/policies', {
      policyCode: code, policyName: `${years}년 UI DoD 승인 정책`, policyType: seven ? 'SEVEN_YEAR_COMMISSION' : 'FOUR_YEAR_COMMISSION', sourceClass: 'GA_POLICY', feeRegimeCode: regime, effectiveFrom: from, effectiveTo: null, basicDocumentVersion: code,
      regulationRefs: ['REG-03'], sourceRefs: ['SRC-022'], sourceDetails: [{ ref: 'SRC-022', title: '격리 인수 시험 정책', explanation: '실제 운영 업무 기준을 변경하지 않는 합성 승인 정책입니다.', url: 'https://example.com/dod' }],
      commissionRules: seven && !short ? [rule, { ...rule, commissionItemId: ids.longItem, feeComponentType: 'LONG_TERM_MAINTENANCE', installmentFrom: 67, calculationType: 'FIXED', ratePct: null, fixedAmount: '12000' }] : [rule],
      feeRegimeRules: [{ feeRegimeCode: regime, salesStartDateFrom: from, basicDocumentVersion: code, maxInstallments: seven ? 84 : 48, ...(seven && !short ? { longTermMaintenanceFrom: 67, longTermMaintenanceTo: 84 } : {}), sourceRefs: ['REG-03', 'SRC-022'] }], parameters: [], clawbackRules: [],
    }, 201)
    let version = created
    for (const [actionType, session] of [['SUBMIT', writer], ['APPROVE', approver], ['ACTIVATE', approver]] as const) {
      version = await session.call(`/policies/${created.policyVersionId}/actions`, { actionType, reason: 'UI DoD 격리 인수 검증', expectedStatus: version.status, expectedUpdatedAt: version.updatedAt })
    }
    const path = `/contracts/${ids.contract}/schedules`
    const context = (await writer.call(`${path}?paymentStage=GA_TO_FC`)).previewContext
    const generated = await writer.call(`${path}/regenerate?paymentStage=GA_TO_FC`, { reason: '승인 정책 인수 검증', regime, policyVersionId: created.policyVersionId, expectedSourceVersion: context.expectedSourceVersion })
    const id = generated[0] as number
    const detail = await writer.call(`/schedules/${id}`)
    return { ...ids, id, code, regime, years, detail }
  } finally {
    await writer.client.dispose()
    await approver.client.dispose()
  }
}

async function downloadCsv(page: Page, name: string) {
  const downloaded = page.waitForEvent('download')
  await page.getByRole('button', { name: 'CSV 내보내기', exact: true }).click()
  const download = await downloaded
  await download.saveAs(name)
  return readFileSync(name, 'utf8').replace(/^\uFEFF/, '')
}

for (const seven of [false, true]) {
  test(`SCHE DoD CT-S0${seven ? 2 : 1}: 실제 승인 정책 ${seven ? 84 : 48}회차·CSV·새 판·복귀`, async ({ page }, info) => {
    await page.setViewportSize({ width: 1280, height: 720 })
    const fixture = await preview(seven)
    const count = seven ? 84 : 48
    const lineCount = seven ? 102 : 48
    expect(fixture.detail.header.installmentCount).toBe(count)
    expect(fixture.detail.lines).toHaveLength(lineCount)
    expect(fixture.detail.header.expectedTotal).toBe(seven ? 2232000 : 2160000)
    expect(fixture.detail.lines.at(-1).dueDate).toBe(seven ? '2036-02-02' : '2031-02-02')
    await login(page, 'settle01', `/schedules?contractNo=${fixture.code}`)
    await page.getByLabel('적용 체계', { exact: true }).selectOption(fixture.regime)
    const row = page.getByRole('row').filter({ has: page.getByRole('link', { name: fixture.code, exact: true }) })
    await expect(row).toContainText(`${count}회`)
    await expect(page.getByText('4년·7년 스케줄은 예정 결과이며 지급 확정 대상이 아닙니다.')).toBeVisible()
    await expect.poll(async () => (await page.locator('p.schedule-data-warning').boundingBox())?.height ?? 999).toBeLessThan(80)
    const before = sql(`SELECT count(*) FROM fgc.schedule_header WHERE contract_id=${fixture.contract}`)
    const listCsv = await downloadCsv(page, info.outputPath('01-list.csv'))
    expect(listCsv).toContain(fixture.code)
    await page.screenshot({ path: info.outputPath('01-real-list.png'), fullPage: true })
    await page.locator('.schedule-table-viewport').evaluate(element => { element.scrollLeft = element.scrollWidth })
    await page.screenshot({ path: info.outputPath('01-real-list-right-columns.png'), fullPage: true })
    await row.getByRole('link', { name: '회차 보기' }).click()
    const table = page.getByRole('table', { name: '회차별 예상 지급 금액' })
    await expect(table.locator('tbody tr')).toHaveCount(lineCount)
    const pico = page.getByRole('button', { name: '업무 비서 AI 열기, 피코' })
    const firstRow = table.locator('tbody tr').filter({ has: page.getByRole('cell', { name: '1', exact: true }) }).getByRole('cell')
    await expect(pico).toBeVisible()
    await expect(firstRow.nth(1)).toHaveText('1')
    await expect(firstRow.nth(2)).toHaveText(seven ? '24,000원' : '45,000원')
    await firstRow.nth(1).scrollIntoViewIfNeeded()
    await expect(firstRow.nth(1)).toBeInViewport()
    await expect(firstRow.nth(2)).toBeInViewport()
    const total = page.getByText(`예상 총액 ${fixture.detail.header.expectedTotal.toLocaleString('ko-KR')}원`, { exact: true })
    await total.scrollIntoViewIfNeeded()
    await expect(total).toBeInViewport()
    await expect(table).toContainText(seven ? '2036-02-02' : '2031-02-02')
    await expect(page.getByRole('button', { name: '확정', exact: true })).toBeDisabled()
    await expect(page.locator('a[href*="/transactions/new"]')).toHaveCount(0)
    const detailCsv = await downloadCsv(page, info.outputPath('02-lines.csv'))
    expect(detailCsv.trim().split(/\r?\n/)).toHaveLength(lineCount + 1)
    expect(detailCsv).toContain(seven ? '2036-02-02' : '2031-02-02')
    await expect(table.locator('tfoot')).toContainText(`${fixture.detail.header.expectedTotal.toLocaleString('ko-KR')}원`)
    await page.screenshot({ path: info.outputPath('02-real-detail.png'), fullPage: true })
    await page.getByRole('region', { name: '회차별 예상 금액 표', exact: true }).evaluate(element => { element.scrollLeft = element.scrollWidth })
    await page.screenshot({ path: info.outputPath('02-real-detail-right-columns.png'), fullPage: true })
    await table.locator('tbody tr').last().scrollIntoViewIfNeeded()
    await page.screenshot({ path: info.outputPath('02-final-installment.png'), fullPage: true })
    expect(sql(`SELECT count(*) FROM fgc.schedule_header WHERE contract_id=${fixture.contract}`)).toBe(before)
    await expect(page.getByRole('button', { name: `${fixture.years}년 예정 새 버전 만들기`, exact: true })).toBeDisabled()
    await page.getByLabel('생성 사유', { exact: true }).fill('확인된 승인 정책 새 판')
    const response = responseFor(page, `/schedules/${fixture.id}/regenerate`)
    await page.getByRole('button', { name: `${fixture.years}년 예정 새 버전 만들기`, exact: true }).click()
    const next = await successful(response)
    await expect(page).toHaveURL(new RegExp(`/schedules/${next.scheduleHeaderId}\\b`))
    await expect(page.getByRole('table', { name: '같은 계약의 스케줄 버전 이력' })).toContainText('v1')
    await expect(page.getByRole('table', { name: '같은 계약의 스케줄 버전 이력' })).toContainText('v2')
    expect(sql(`SELECT active_yn FROM fgc.schedule_header WHERE schedule_header_id=${fixture.id}`)).toBe('f')
    await page.getByRole('link', { name: '목록으로', exact: true }).click()
    await expect(page.getByLabel('적용 체계', { exact: true })).toHaveValue(fixture.regime)
    await expect(page.getByLabel('계약번호', { exact: true })).toHaveValue(fixture.code)
    await expect(page.getByLabel('용도', { exact: true })).toHaveValue('SIMULATION')
    await page.screenshot({ path: info.outputPath('03-return-context.png'), fullPage: true })
  })
}

test('SCHE DoD: 실제 12회차 정액 정책은 가짜 48/84행을 만들지 않는다', async ({ page }, info) => {
  for (const seven of [false, true]) {
    const fixture = await preview(seven, true)
    expect(fixture.detail.lines).toHaveLength(12)
    expect(fixture.detail.header.installmentCount).toBe(12)
    expect(fixture.detail.header.expectedTotal).toBe(144000)
    await login(page, 'settle01', `/schedules/${fixture.id}`)
    await expect(page.getByRole('table', { name: '회차별 예상 지급 금액' }).locator('tbody tr')).toHaveCount(12)
    await page.screenshot({ path: info.outputPath(`real-${fixture.years}-year-12-fixed.png`), fullPage: true })
  }
})

test('SCHE DoD: 목록·상세 403/409/422/500은 0건·0원으로 위장하지 않고 재시도한다', async ({ page }, info) => {
  const { id } = await preview(false)
  await login(page, 'settle01', '/schedules')
  for (const detail of [false, true]) {
    const endpoint = detail ? `**/api/v1/schedules/${id}` : '**/api/v1/schedules?*'
    for (const status of [403, 409, 422, 500]) {
      await page.route(endpoint, route => route.fulfill({ status, json: { data: null, error: { code: `DOD-${status}`, message: `DoD ${status} 조회 실패` }, requestId: `dod-${status}` } }))
      await page.goto(detail ? `/schedules/${id}` : '/schedules')
      await expect(page.getByRole('alert').filter({ hasText: `DoD ${status} 조회 실패` }).first()).toBeVisible()
      await expect(page.getByRole('button', { name: 'CSV 내보내기', exact: true })).toBeDisabled()
      if (detail) {
        await expect(page.getByRole('button', { name: '확정', exact: true })).toBeDisabled()
        await expect(page.getByRole('table', { name: '회차별 예상 지급 금액' }).locator('tfoot')).not.toContainText('0원')
      } else await expect(page.getByText('조회 결과가 없습니다.', { exact: true })).toHaveCount(0)
      await page.screenshot({ path: info.outputPath(`${detail ? 'detail' : 'list'}-${status}.png`), fullPage: true })
      await page.unroute(endpoint)
      await page.getByRole('button', { name: '다시 시도', exact: true }).first().click()
      await expect(page.getByRole('alert').filter({ hasText: `DoD ${status} 조회 실패` })).toHaveCount(0)
      await expect(page.getByRole('button', { name: 'CSV 내보내기', exact: true })).toBeEnabled()
    }
  }
  // This is the existing authentication recovery, rather than a fake successful empty result.
  sql("UPDATE fgc.auth_refresh_token SET revoked_at=clock_timestamp(),revoked_reason='LOGOUT' WHERE revoked_at IS NULL AND user_id=(SELECT user_id FROM fgc.app_user WHERE login_id='settle01')")
  await page.reload()
  await expect(page).toHaveURL(/\/login/)
  await page.screenshot({ path: info.outputPath('real-401-login.png'), fullPage: true })
})

test('SCHE DoD: 역할별 직접 API/URL·ID 변경과 읽기 전용 버튼을 서버에서 제한한다', async ({ page }, info) => {
  const ids = [(await preview(false)).id, (await preview(true)).id]
  for (const account of ['admin', 'settle01', 'gaadmin', 'audit01', 'agent01']) {
    if (account === 'agent01') sql("UPDATE fgc.app_user SET account_status='ACTIVE',password_hash=(SELECT password_hash FROM fgc.app_user WHERE login_id='settle01') WHERE login_id='agent01'")
    const session = await actor(account)
    try {
      for (const path of ['/schedules', `/schedules/${ids[0]}`, `/schedules/${ids[1]}`, `/schedules/${ids[0]}/versions`, `/schedules/${ids[0]}/export.csv`]) {
        const response = await session.raw(path)
        expect(response.status(), `${account} ${path}`).toBe(account === 'agent01' ? 403 : 200)
      }
      if (['gaadmin', 'audit01', 'agent01'].includes(account)) {
        for (const suffix of ['confirm', 'regenerate']) expect((await session.raw(`/schedules/${ids[0]}/${suffix}`, 'POST', { reason: '권한 우회 시도' })).status()).toBe(403)
      } else {
        const response = await session.raw(`/schedules/${ids[0]}/confirm`, 'POST', {})
        expect(response.status()).toBe(409)
        expect((await response.json()).error.code).toBe('FGC-IMPL-SCHEDULE_PREVIEW_ONLY')
      }
      const missing = await session.raw('/schedules/999999999')
      expect(missing.status()).toBe(account === 'agent01' ? 403 : 400)
      expect((await missing.json()).data).toBeNull()
    } finally { await session.client.dispose() }
    await login(page, account, `/schedules/${ids[0]}`)
    if (account === 'agent01') {
      await expect(page.getByRole('table', { name: '회차별 예상 지급 금액' })).toHaveCount(0)
    } else {
      await expect(page.getByRole('table', { name: '회차별 예상 지급 금액' })).toBeVisible()
      if (['gaadmin', 'audit01'].includes(account)) {
        await expect(page.getByRole('button', { name: '확정', exact: true })).toBeDisabled()
        await expect(page.getByRole('button', { name: '새 버전 만들기', exact: true })).toBeDisabled()
        await expect(page.getByText('확정과 재생성은 정산담당자와 시스템관리자만 할 수 있습니다.')).toBeVisible()
        await expect(page.locator('a[href*="/transactions/new"]')).toHaveCount(0)
      }
    }
    await page.screenshot({ path: info.outputPath(`role-${account}.png`), fullPage: true })
  }
  const anonymous = await request.newContext({ baseURL: 'http://127.0.0.1:15173' })
  try { expect((await anonymous.get(`/api/v1/schedules/${ids[0]}`)).status()).toBe(401) } finally { await anonymous.dispose() }
})

test('SCHE DoD: 로딩·늦은 응답·정상 0건·null/0/음수 표시를 구분한다', async ({ page }, info) => {
  const fixture = await preview(false)
  await login(page, 'settle01', `/schedules?regime=${fixture.regime}&purpose=SIMULATION`)
  await expect(page.getByRole('link', { name: fixture.code, exact: true })).toBeVisible()
  let release!: () => void
  let finish!: () => void
  const pending = new Promise<void>(resolve => { release = resolve })
  const handled = new Promise<void>(resolve => { finish = resolve })
  await page.route('**/api/v1/schedules?*', async route => {
    if (new URL(route.request().url()).searchParams.get('contractNo') !== fixture.code) return route.continue()
    const result = await route.fetch()
    await pending
    try { await route.fulfill({ response: result }) } finally { finish() }
  })
  await page.getByLabel('계약번호', { exact: true }).fill(fixture.code)
  await page.getByRole('button', { name: '조회', exact: true }).click()
  await expect(page.locator('[data-guide-target="schedule.results"]')).toHaveAttribute('aria-busy', 'true')
  await expect(page.getByRole('button', { name: 'CSV 내보내기', exact: true })).toBeDisabled()
  await page.screenshot({ path: info.outputPath('01-loading.png'), fullPage: true })
  await page.getByLabel('계약번호', { exact: true }).fill('DOD-NO-SUCH-CONTRACT')
  await page.getByRole('button', { name: '조회', exact: true }).click()
  await expect(page.locator('[data-guide-target="schedule.results"]')).toHaveAttribute('aria-busy', 'false')
  release()
  await handled
  await expect(page.getByRole('link', { name: fixture.code, exact: true })).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('02-real-zero-after-late-response.png'), fullPage: true })
  await page.unroute('**/api/v1/schedules?*')
  // Nullable/zero/negative boundary values are an explicit response fault fixture,
  // not a claim that the policy engine produced a missing or negative amount.
  await page.route('**/api/v1/schedules?*', async route => {
    const result = await route.fetch()
    const envelope = await result.json()
    const base = envelope.data.content[0]
    envelope.data.content = [
      { ...base, scheduleHeaderId: 900001, contractNo: 'DOD-NULL', installmentCount: null, lineCount: null, expectedTotal: null },
      { ...base, scheduleHeaderId: 900002, contractNo: 'DOD-ZERO', expectedTotal: 0 },
      { ...base, scheduleHeaderId: 900003, contractNo: 'DOD-NEGATIVE', expectedTotal: -1234 },
    ]
    envelope.data.totalElements = 3
    await route.fulfill({ response: result, json: envelope })
  })
  await page.goto(`/schedules?regime=${fixture.regime}&purpose=SIMULATION`)
  await expect(page.getByRole('row').filter({ hasText: 'DOD-NULL' })).toContainText('자료 없음')
  await expect(page.getByRole('row').filter({ hasText: 'DOD-ZERO' })).toContainText('0원')
  await expect(page.getByRole('row').filter({ hasText: 'DOD-NEGATIVE' })).toContainText('-1,234원')
  await page.screenshot({ path: info.outputPath('03-response-null-zero-negative.png'), fullPage: true })
})

test('SCHE DoD CT-C02: 실제 상태 효과 API와 회차 원천 사건 표시를 대조한다', async ({ page }, info) => {
  const fixture = await preview(false)
  const line = fixture.detail.lines[0].scheduleLineId
  // The real worker itself is exercised by ContractStatusHistoryIntegrationTest.
  // Here persisted effects let the browser/API presentation be checked separately.
  const event = sql(`WITH event AS (INSERT INTO fgc.contract_status_event(contract_id,event_seq,new_status,effective_at,received_at,source_system,source_event_key,data_origin)
    VALUES (${fixture.contract},1,'UNPAID','2027-04-01T00:00:00+09','2027-04-01T00:00:00+09','DOD','DOD-HOLD','MANUAL') RETURNING contract_status_event_id)
    SELECT contract_status_event_id FROM event`)
  sql(`UPDATE fgc.schedule_line SET line_status='HOLD' WHERE schedule_line_id=${line};
    INSERT INTO fgc.contract_status_event_effect(contract_status_event_id,processing_job,entity_type,entity_id,previous_state,next_state,reason_code)
    VALUES (${event},'PaymentHoldJob','SCHEDULE_LINE',${line},'PLANNED','HOLD','STATUS_UNPAID')`)
  await login(page, 'settle01', `/schedules/${fixture.id}`)
  const row = page.getByRole('table', { name: '회차별 예상 지급 금액' }).locator('tbody tr').filter({ hasText: '상태 변경 있음' })
  await expect(row).toContainText('상태 변경 있음')
  await expect(row).toContainText('보류')
  const response = responseFor(page, `/schedules/${fixture.id}`, 'GET')
  await page.reload()
  const detail = await successful(response)
  expect(detail.lines.find((entry: { scheduleLineId: number }) => entry.scheduleLineId === line).sourceStatusEventId).toBe(Number(event))
  await page.getByRole('region', { name: '회차별 예상 금액 표', exact: true }).evaluate(element => { element.scrollLeft = element.scrollWidth })
  await expect(row.getByText('상태 변경 있음', { exact: true })).toBeInViewport()
  await page.screenshot({ path: info.outputPath('real-source-status-event.png'), fullPage: true })
})

test('SCHE DoD: 생성 응답 유실은 같은 키로 복구하고 409 입력을 보존한다', async ({ page }, info) => {
  const fixture = await preview(false)
  await login(page, 'settle01', `/schedules/${fixture.id}`)
  await page.getByLabel('생성 사유', { exact: true }).fill('유실 뒤 보존할 사유')
  sql(`UPDATE fgc.insurance_contract SET monthly_equivalent_first_premium=200000 WHERE contract_id=${fixture.contract}`)
  const stale = responseFor(page, `/schedules/${fixture.id}/regenerate`)
  await page.getByRole('button', { name: '4년 예정 새 버전 만들기', exact: true }).click()
  expect((await stale).status()).toBe(409)
  await expect(page.getByLabel('생성 사유', { exact: true })).toHaveValue('유실 뒤 보존할 사유')
  await page.screenshot({ path: info.outputPath('real-409-preserved.png'), fullPage: true })
  await page.getByRole('button', { name: '최신 입력 다시 조회', exact: true }).click()
  await expect(page.getByRole('button', { name: '4년 예정 새 버전 만들기', exact: true })).toBeEnabled()
  const keys: string[] = []
  let lose = true
  let release!: () => void
  const pending = new Promise<void>(resolve => { release = resolve })
  await page.route(`**/api/v1/schedules/${fixture.id}/regenerate`, async route => {
    keys.push(route.request().headers()['idempotency-key'])
    const result = await route.fetch()
    expect(result.status()).toBe(200)
    if (lose) { lose = false; await pending; await route.abort('failed') } else await route.fulfill({ response: result })
  })
  await page.getByRole('button', { name: '4년 예정 새 버전 만들기', exact: true }).click()
  await expect(page.getByRole('button', { name: '4년 예정 새 버전 만들기', exact: true })).toBeDisabled()
  release()
  await expect(page.getByRole('button', { name: '생성 결과 다시 확인', exact: true })).toBeEnabled()
  await expect(page.getByLabel('생성 사유', { exact: true })).toHaveValue('유실 뒤 보존할 사유')
  await page.screenshot({ path: info.outputPath('lost-response-same-request.png'), fullPage: true })
  await page.getByRole('button', { name: '생성 결과 다시 확인', exact: true }).click()
  await expect(page).not.toHaveURL(new RegExp(`/schedules/${fixture.id}(?:\\?|$)`))
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBeTruthy()
  expect(keys[1]).toBe(keys[0])
  expect(sql(`SELECT count(*) FROM fgc.schedule_header WHERE contract_id=${fixture.contract}`)).toBe('2')
})
