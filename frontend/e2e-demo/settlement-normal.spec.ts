import { selectEntity } from '../e2e/selectEntity'
import { test, expect, login, api, responseFor, successful, sql } from './fixtures'

test.use({ video: 'off' })
// Start through the production guide, then verify the real payment and settlement operations.
// SYSTEM_ADMIN holds both CAN_PROCESS and CAN_FINALIZE_VALIDATION; role handoffs are settlement-completion.spec.ts.
test('새 업무 가이드에서 계약 등록 → 계약별 지급 확정 → 대사 일치 → 최종 확정', async ({ page }, info) => {
  const errors: string[] = []
  const writes: string[] = []
  const proofReads: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  // Keep every business POST; only the exact read-only proof endpoint is recorded separately.
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path.startsWith('/api/v1/') && !path.startsWith('/api/v1/auth/')) {
      if (path === '/api/v1/assistant/prechecks/revalidate') proofReads.push(path)
      else writes.push(path)
    }
  })
  // api() signs in the same demo admin separately and one account keeps one session, so read setup data first.
  await page.goto('login')
  const seed = await api(page, '/contracts?contractNo=FGC-FGL01-202607-0001&page=1&size=20')
  const original = await api(page, `/contracts/${seed.content[0].contractId}`)
  await login(page, 'admin', '/?month=2026-07')
  await page.getByRole('button', { name: '전체 업무 가이드', exact: true }).click()
  const guide = page.getByRole('complementary', { name: '전체 업무 가이드' })
  await page.getByRole('dialog', { name: '전체 업무 가이드', exact: true }).getByRole('button', { name: '계약 생성부터 시작' }).click()
  await expect(page).toHaveURL(/\/contracts\/new/)
  await expect(guide).toContainText('1/9')
  expect(writes).toEqual([])
  await guide.getByRole('button', { name: '접기', exact: true }).click()

  await page.locator('input[name=contractDate]').fill(original.contractDate)
  await page.locator('select[name=insurerId]').selectOption(String(original.insurerId))
  await selectEntity(page, page.locator('[role=combobox][name=productOfferingId]'), String(original.productOfferingId))
  await page.locator('input[name=contractNo]').fill('E2E-FUN088-NORMAL')
  await selectEntity(page, page.locator('[role=combobox][name=agentId]'), String(original.agentId))
  for (const field of ['premiumPerCycleAmount', 'firstPremiumAmount', 'monthlyEquivalentFirstPremium'])
    await page.locator(`input[name=${field}]`).fill(String(original[field]))
  const term = page.locator('input[name=paymentTermMonths]')
  if (await term.isEditable()) await term.fill(String(original.paymentTermMonths))
  const created = responseFor(page, '/contracts')
  await page.locator('button[type=submit]').click()
  const cid = Number((await successful(created)).contractId)
  expect(Number.isSafeInteger(cid) && cid > 0).toBe(true)
  await expect(page).toHaveURL(new RegExp(`/contracts/${cid}(?:\\?|$)`))

  // Isolated input fixture, as in settlement-completion.spec.ts: only this contract's one current line of 40 is due,
  // so a payment of 40 must reconcile. Never write validation/reconciliation outcomes or FINALIZED state.
  sql(`TRUNCATE fgc.commission_transaction CASCADE;
    TRUNCATE fgc.contract_status_event CASCADE;
    UPDATE fgc.insurance_contract SET current_status='MATURED' WHERE contract_id <> ${cid};
    UPDATE fgc.schedule_header SET status='HOLD' WHERE contract_id <> ${cid} AND status NOT IN ('ADJUSTED', 'CANCELLED');`)
  const source = JSON.parse(
    sql(`SELECT row_to_json(x) FROM (
    SELECT sl.*, sh.schedule_header_id AS header_id FROM fgc.schedule_line sl
    JOIN fgc.schedule_header sh USING(schedule_header_id)
    JOIN fgc.commission_item i USING(commission_item_id)
    WHERE sh.contract_id=${cid} AND sh.active_yn AND sh.payment_stage='GA_TO_FC'
    AND sl.due_month=DATE '2026-07-01' AND i.item_code='BASE_COMMISSION'
    ORDER BY sl.schedule_line_id LIMIT 1) x`),
  )
  sql(`UPDATE fgc.schedule_line SET line_status='HOLD' WHERE schedule_line_id <> ${source.schedule_line_id};
    UPDATE fgc.schedule_line SET expected_amount=40 WHERE schedule_line_id=${source.schedule_line_id};`)
  const amount = 40

  await guide.getByRole('button', { name: '종료', exact: true }).click()
  await page.goto(`/transactions/new?contractId=${cid}&month=2026-07`)
  const saving = responseFor(page, '/transactions/batch')
  await page.getByRole('button', { name: '임시저장', exact: true }).click()
  const payments = await successful(saving)
  expect(payments).toHaveLength(1)
  const pid = payments[0].commissionTransactionId
  expect(payments[0].amount).toBe(amount)
  const checking = responseFor(page, `/transactions/${pid}/precheck`)
  await page.getByRole('button', { name: '사전검증', exact: true }).click()
  expect((await successful(checking)).confirmable).toBe(true)
  await page.getByRole('button', { name: '확정', exact: true }).click()
  const confirming = responseFor(page, `/transactions/${pid}/confirm`)
  await page.getByRole('dialog').getByRole('button', { name: '확인하고 확정', exact: true }).click()
  expect((await successful(confirming)).status).toBe('CONFIRMED')
  await page.goto('/validation-runs?month=2026-07')
  const creating = responseFor(page, '/validation-runs')
  await page.getByRole('button', { name: '실행 생성' }).click()
  const rid = Number((await successful(creating)).validationRunId)
  await expect(page).toHaveURL(new RegExp(`/validation-runs/${rid}\\b`))
  await page.getByRole('button', { name: '실행', exact: true }).click()
  await expect
    .poll(() => sql(`SELECT status FROM fgc.validation_run WHERE validation_run_id=${rid}`), { timeout: 120000 })
    .toBe('COMPLETED')
  const reconId = Number(
    sql(`SELECT reconciliation_run_id FROM fgc.reconciliation_run
      WHERE validation_run_id=${rid} AND insurer_id=${original.insurerId} AND payment_stage='GA_TO_FC'`),
  )
  expect(Number.isSafeInteger(reconId) && reconId > 0).toBe(true)
  await page.goto(`/reconciliations?month=2026-07&runId=${reconId}`)
  // Keep the browser's admin session alive while independently reading the outcomes.
  // A second admin login would trigger a 401 redirect racing with the next navigation.
  const results = await api(page, `/reconciliations/${reconId}/results?page=1&size=100`, undefined, 'audit01')
  const open = await api(page, `/exceptions?status=OPEN&validationRunId=${rid}&page=1&size=100`, undefined, 'audit01')
  expect(results.summary.exceptionCount).toBe(0)
  expect(results.summary.matchedCount).toBe(results.summary.resultCount)
  expect(open.totalElements).toBe(0)
  await page.goto(`/validation-runs/${rid}?month=2026-07`)
  await expect(page.getByRole('button', { name: '확정', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '확정', exact: true }).click()
  const finalized = responseFor(page, `/validation-runs/${rid}/finalize`)
  await page.getByRole('dialog').getByRole('button', { name: '확정', exact: true }).click()
  expect((await successful(finalized)).status).toBe('FINALIZED')
  await page.getByRole('dialog', { name: '확정했습니다' }).getByRole('button', { name: '확인', exact: true }).click()
  expect(sql(`SELECT status FROM fgc.validation_run WHERE validation_run_id=${rid}`)).toBe('FINALIZED')
  expect(errors).toEqual([])
  // The user's own button clicks are the only business writes; the guide added none.
  expect(writes).toEqual([
    '/api/v1/contracts',
    '/api/v1/transactions/batch',
    `/api/v1/transactions/${pid}/precheck`,
    `/api/v1/transactions/${pid}/precheck`,
    `/api/v1/transactions/${pid}/confirm`,
    '/api/v1/validation-runs',
    `/api/v1/validation-runs/${rid}/execute`,
    `/api/v1/validation-runs/${rid}/finalize`,
  ])
  await info.attach('normal-flow-completed', { body: await page.screenshot(), contentType: 'image/png' })
  await info.attach('normal-flow-evidence', {
    body: JSON.stringify({ cid, pid, rid, reconId, results: results.summary, open: open.totalElements, writes, proofReads, errors }),
    contentType: 'application/json',
  })
})
