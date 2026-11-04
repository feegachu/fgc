import { selectEntity } from '../e2e/selectEntity'
import { test, expect, login, api, responseFor, successful, sql } from './fixtures'

test.use({ video: 'off' })
// One guide session passes all nine logical steps on the normal path: reconciliation matches, so step 8 is skipped.
// SYSTEM_ADMIN holds both CAN_PROCESS and CAN_FINALIZE_VALIDATION; role handoffs are settlement-completion.spec.ts.
test('FUN-088 정상 흐름: 새 계약 등록 → 대사 일치 → 예외 단계 없이 최종 확정 (한 번의 안내)', async ({ page }, info) => {
  const errors: string[] = []
  const writes: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  // Every browser POST except sign-in, so no guide write can hide outside a path filter.
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path.startsWith('/api/v1/') && !path.startsWith('/api/v1/auth/'))
      writes.push(path)
  })
  // api() signs in the same demo admin separately and one account keeps one session, so read setup data first.
  await page.goto('login')
  const seed = await api(page, '/contracts?contractNo=FGC-FGL01-202607-0001&page=1&size=20')
  const original = await api(page, `/contracts/${seed.content[0].contractId}`)
  const items = await api(page, '/base/commission-items?asOf=2026-07-01')
  const item = items.find((entry: { itemCode: string }) => entry.itemCode === 'BASE_COMMISSION')
  await login(page, 'admin', '/?month=2026-07')
  await page.getByRole('button', { name: '전체 업무 가이드', exact: true }).click()
  const guide = page.getByRole('complementary', { name: '전체 업무 가이드' })
  const next = guide.getByRole('button', { name: '현재 결과로 이어서 안내' })
  await guide.getByRole('button', { name: '안내 시작', exact: true }).click()
  await expect(page).toHaveURL(/\/contracts\/new/)
  await expect(guide).toContainText('1/9')
  expect(writes).toEqual([])

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
    UPDATE fgc.schedule_header SET status='HOLD' WHERE contract_id <> ${cid};`)
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

  await next.click()
  await expect(page).toHaveURL(new RegExp(`/schedules/${source.header_id}(?:\\?|$)`))
  await expect(guide).toContainText('2/9')
  await guide.getByRole('button', { name: '스케줄 확인 완료' }).click()
  await expect(page).toHaveURL(/\/transactions\/new/)
  await expect(guide).toContainText('3/9')

  await selectEntity(page, page.locator('#recipient'), String(original.agentId))
  await selectEntity(page, page.locator('#item'), String(item.commissionItemId))
  await page.locator('#bizKey').fill('E2E-FUN088-NORMAL')
  await page.locator('#amount').fill(String(amount))
  if (!(await page.locator('[data-contract-id]').count())) await page.locator('#btn-add-attr').click()
  await selectEntity(page, page.locator('[data-contract-id]'), String(cid))
  await page.locator('[data-attr-date]').fill(source.due_date)
  await page.locator('[data-attr-amount]').fill(String(amount))
  const saving = responseFor(page, '/transactions')
  await page.locator('#btn-save').click()
  const pid = (await successful(saving)).commissionTransactionId
  await next.click()
  await expect(guide).toContainText('4/9')
  const checking = responseFor(page, `/transactions/${pid}/precheck`)
  await page.locator('#btn-precheck').click()
  expect((await successful(checking)).confirmable).toBe(true)
  await next.click()
  await expect(guide).toContainText('5/9')
  await page.locator('#btn-confirm').click()
  const confirming = responseFor(page, `/transactions/${pid}/confirm`)
  await page.getByRole('dialog').getByRole('button', { name: '확정 실행' }).click()
  expect((await successful(confirming)).status).toBe('CONFIRMED')
  await expect(page).toHaveURL(/\/transactions\?/)

  await next.click()
  await expect(page).toHaveURL(/\/validation-runs\?month=2026-07/)
  const creating = responseFor(page, '/validation-runs')
  await page.getByRole('button', { name: '실행 생성' }).click()
  const rid = Number((await successful(creating)).validationRunId)
  await next.click()
  await expect(guide).toContainText('6/9')
  await expect(guide).toContainText('월 통합검증 계산이 끝나지 않았습니다')
  await page.getByRole('button', { name: '실행', exact: true }).click()
  await expect
    .poll(() => sql(`SELECT status FROM fgc.validation_run WHERE validation_run_id=${rid}`), { timeout: 120000 })
    .toBe('COMPLETED')
  await next.click()
  await expect(page).toHaveURL(/\/reconciliations\?/)

  const reconId = Number(
    sql(`SELECT reconciliation_run_id FROM fgc.reconciliation_run
      WHERE validation_run_id=${rid} AND insurer_id=${original.insurerId} AND payment_stage='GA_TO_FC'`),
  )
  expect(Number.isSafeInteger(reconId) && reconId > 0).toBe(true)
  await page
    .getByRole('table', { name: '검색조건에 해당하는 대사 실행 이력' })
    .getByRole('row')
    .filter({ has: page.getByRole('cell', { name: String(reconId), exact: true }) })
    .click()
  // The guide's own reads decide the branch; record them instead of re-reading through another session.
  const resultsRead = page.waitForResponse((response) => {
    const url = new URL(response.url())
    // The page's own results query always sends sort; the guide's read does not.
    return (
      url.pathname === `/api/v1/reconciliations/${reconId}/results` &&
      !url.searchParams.has('sort') &&
      response.request().method() === 'GET'
    )
  })
  const openRead = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return (
      url.pathname === '/api/v1/exceptions' &&
      url.searchParams.get('status') === 'OPEN' &&
      url.searchParams.get('validationRunId') === String(rid)
    )
  })
  await next.click()
  const results = (await (await resultsRead).json()).data
  const open = (await (await openRead).json()).data
  expect(results.summary.exceptionCount).toBe(0)
  expect(results.summary.matchedCount).toBe(results.summary.resultCount)
  expect(open.totalElements).toBe(0)
  // A matched reconciliation without open exceptions goes from step 7 straight to step 9.
  await expect(guide).toContainText('9/9')
  await expect(guide).toContainText('8개 조건을 통과했습니다')
  await expect(page).toHaveURL(new RegExp(`/validation-runs/${rid}(?:\\?|$)`))
  await expect(guide).not.toContainText('전체 업무 안내 완료')

  await page.getByRole('button', { name: '확정', exact: true }).click()
  const finalized = responseFor(page, `/validation-runs/${rid}/finalize`)
  await page.getByRole('dialog').getByRole('button', { name: '확정', exact: true }).click()
  expect((await successful(finalized)).status).toBe('FINALIZED')
  await page.getByRole('dialog', { name: '확정했습니다' }).getByRole('button', { name: '확인', exact: true }).click()
  await next.click()
  await expect(guide).toContainText('전체 업무 안내 완료')
  expect(sql(`SELECT status FROM fgc.validation_run WHERE validation_run_id=${rid}`)).toBe('FINALIZED')
  expect(errors).toEqual([])
  // The user's own button clicks are the only business writes; the guide added none.
  expect(writes).toEqual([
    '/api/v1/contracts',
    '/api/v1/transactions',
    `/api/v1/transactions/${pid}/precheck`,
    `/api/v1/transactions/${pid}/confirm`,
    '/api/v1/validation-runs',
    `/api/v1/validation-runs/${rid}/execute`,
    `/api/v1/validation-runs/${rid}/finalize`,
  ])
  await info.attach('normal-flow-completed', { body: await page.screenshot(), contentType: 'image/png' })
  await info.attach('normal-flow-evidence', {
    body: JSON.stringify({ cid, pid, rid, reconId, results: results.summary, open: open.totalElements, writes, errors }),
    contentType: 'application/json',
  })
})
