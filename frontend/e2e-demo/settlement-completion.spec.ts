import { test, expect, login, api, responseFor, successful, sql } from './fixtures'

test.use({ video: 'off' })
test('원천 지급 정정 → 재검증·재대사 → 역할 인계 후 최종 확정', async ({ page }, info) => {
  const errors: string[] = []
  const writes: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && /^\/api\/v1\/(validation-runs|reconciliations|transactions)(\/|$)/.test(path))
      writes.push(path)
  })
  await login(page, 'settle01', '/?month=2026-07')
  const found = await api(page, '/contracts?contractNo=FGC-FGL01-202607-0002&page=1&size=20')
  const contract = await api(page, `/contracts/${found.content[0].contractId}`)
  const cid = Number(contract.contractId)
  expect(Number.isSafeInteger(cid)).toBe(true)
  // Isolated input fixture only: remove unrelated demo payments/events and exclude other contracts.
  // Never write validation/reconciliation outcomes, exception resolutions or FINALIZED state.
  sql(`TRUNCATE fgc.commission_transaction CASCADE;
    TRUNCATE fgc.contract_status_event CASCADE;
    UPDATE fgc.insurance_contract SET current_status='MATURED' WHERE contract_id <> ${cid};
    UPDATE fgc.schedule_header SET status='HOLD' WHERE contract_id <> ${cid} AND status NOT IN ('ADJUSTED', 'CANCELLED');`)
  await page.goto(`/contracts/${cid}?month=2026-07&tab=schedules`)
  const generated = page.waitForResponse(
    (r) => r.url().includes('/schedules/regenerate') && r.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '스케줄 재생성', exact: true }).click()
  await successful(generated)
  const source = JSON.parse(
    sql(`SELECT row_to_json(x) FROM (
    SELECT sl.*, sh.schedule_header_id AS header_id FROM fgc.schedule_line sl
    JOIN fgc.schedule_header sh USING(schedule_header_id)
    JOIN fgc.commission_item i USING(commission_item_id)
    WHERE sh.contract_id=${cid} AND sh.active_yn AND sh.payment_stage='GA_TO_FC'
    AND sl.due_month=DATE '2026-07-01' AND i.item_code='BASE_COMMISSION'
    ORDER BY sl.schedule_line_id LIMIT 1) x`),
  )
  sql(`UPDATE fgc.schedule_line SET line_status='HOLD' WHERE schedule_line_id <> ${source.schedule_line_id};`)
  // A small, explicit schedule input keeps this correction scenario separate from arbitrage policy tests.
  // Both source values are set before the first payment or validation; all results remain server-produced.
  sql(`UPDATE fgc.schedule_line SET expected_amount=40 WHERE schedule_line_id=${source.schedule_line_id};`)
  const amount = 40
  expect(amount).toBeGreaterThan(20)
  const payment = await api(page, '/transactions', {
    paymentStage: 'GA_TO_FC',
    sourceType: 'GA_MANUAL_PAYMENT',
    sourceBusinessKey: 'E2E-FUN088-CORRECTION',
    contractId: cid,
    agentId: contract.agentId,
    commissionItemId: source.commission_item_id,
    cashflowType: 'PAYMENT',
    settlementMonth: '2026-07-01',
    scheduledPaymentDate: source.due_date,
    amount: 20,
    attributions: [
      {
        contractId: cid,
        amount: 20,
        attributionDate: source.due_date,
        attributionMethod: 'DIRECT',
        inclusionDecisionStatus: 'INCLUDED',
        inclusionDecisionReason: '단일 계약 직접 귀속',
        allocationBasis: 'DIRECT',
        evidenceRef: 'E2E-FUN088',
      },
    ],
  })
  const pid = payment.commissionTransactionId
  expect((await api(page, `/transactions/${pid}/precheck`, {})).confirmable).toBe(true)
  await api(page, `/transactions/${pid}/confirm`, {})
  await page.goto('/validation-runs?month=2026-07')
  const created = responseFor(page, '/validation-runs')
  await page.getByRole('button', { name: '실행 생성', exact: true }).click()
  const run = await successful(created)
  // A person resumes after the detail screen opens; resuming mid-navigation makes the guide report a screen change.
  await expect(page).toHaveURL(new RegExp(`/validation-runs/${run.validationRunId}\\b`))
  await page.getByRole('button', { name: '실행', exact: true }).click()
  await expect
    .poll(async () => (await api(page, `/validation-runs/${run.validationRunId}/progress`)).status, { timeout: 120000 })
    .toBe('COMPLETED')
  const runs = await api(page, '/reconciliations?month=2026-07&stage=GA_TO_FC&page=1&size=100')
  const recon = runs.content.find(
    (r: { validationRunId: number; insurerId: number }) =>
      r.validationRunId === run.validationRunId && r.insurerId === contract.insurerId,
  )
  await page.goto(`/reconciliations?month=2026-07&runId=${recon.reconciliationRunId}&historyView=table`)
  await page.getByRole('button', { name: '표 보기', exact: true }).click()
  const history = page.getByRole('table', { name: '검색조건에 해당하는 대사 실행 이력' })
  await history
    .getByRole('row')
    .filter({ has: page.getByRole('cell', { name: String(recon.reconciliationRunId), exact: true }) })
    .click()
  const before = await api(page, `/reconciliations/${recon.reconciliationRunId}/results?page=1&size=100`)
  expect(before.summary.exceptionCount).toBeGreaterThan(0)
  const jid = Number(
    sql(
      `SELECT journal_header_id FROM fgc.journal_header WHERE source_entity_type='COMMISSION_TRANSACTION' AND source_entity_id='${pid}' AND status='POSTED'`,
    ),
  )
  await page.goto(`/journals?month=2026-07&ledgerView=all&evidence=${jid}`)
  const journal = await api(page, `/journals/${jid}`)
  await expect(page.getByRole('region', { name: '분개 상세' })).toContainText(journal.journalNo)
  await page.getByRole('button', { name: '역분개 + 재기표', exact: true }).click()
  await page.getByRole('textbox', { name: '정정 요청 사유', exact: true }).fill('예상 지급 대비 과소 지급 원천 정정')
  await page.getByRole('button', { name: '예외함에서 정정 계속', exact: true }).click()
  await page.getByRole('combobox', { name: '조치', exact: true }).selectOption('START_REVIEW')
  await page.getByLabel('처리 사유').fill('원분개와 지급 예정 금액 확인')
  await page.getByRole('button', { name: '처리 저장', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '원분개 역분개, 신규 재기표' })
  await expect(dialog).toBeVisible()
  await dialog
    .getByRole('group', { name: '1번 줄', exact: true })
    .getByLabel('차변', { exact: false })
    .fill(String(amount))
  await dialog
    .getByRole('group', { name: '2번 줄', exact: true })
    .getByLabel('대변', { exact: false })
    .fill(String(amount))
  await dialog.getByLabel('정정 사유', { exact: false }).fill('스케줄 원천 금액과 일치하도록 지급 금액 정정')
  const corrected = page.waitForResponse(
    (r) => r.url().endsWith('/journal-correction') && r.request().method() === 'POST',
  )
  await dialog.getByRole('button', { name: '정정 실행', exact: true }).click()
  const correction = await successful(corrected)
  const replacement = Number(
    sql(`SELECT replacement_payment_id FROM fgc.payment_journal_correction WHERE original_payment_id=${pid}`),
  )
  expect((await api(page, `/transactions/${pid}`)).status).toBe('CANCELLED')
  expect((await api(page, `/transactions/${replacement}`)).amount).toBe(amount)
  const revised = Number(
    sql(
      `SELECT reconciliation_run_id FROM fgc.reconciliation_run WHERE corrects_reconciliation_run_id=${recon.reconciliationRunId}`,
    ),
  )
  expect((await api(page, `/reconciliations/${revised}/results?page=1&size=100`)).summary.exceptionCount).toBe(0)
  if (await dialog.isVisible()) await dialog.getByRole('button', { name: '닫기', exact: true }).click()
  await page.goto('/validation-runs?month=2026-07')
  const recreated = responseFor(page, '/validation-runs')
  await page.getByRole('button', { name: '실행 생성', exact: true }).click()
  const rerun = await successful(recreated)
  await page.getByRole('button', { name: '실행', exact: true }).click()
  await expect
    .poll(async () => (await api(page, `/validation-runs/${rerun.validationRunId}/progress`)).status, {
      timeout: 120000,
    })
    .toBe('COMPLETED')
  const updatedRuns = await api(page, '/reconciliations?month=2026-07&stage=GA_TO_FC&page=1&size=100')
  const latest = updatedRuns.content.find(
    (r: { validationRunId: number; insurerId: number }) =>
      r.validationRunId === rerun.validationRunId && r.insurerId === contract.insurerId,
  )
  await page.goto(`/reconciliations?month=2026-07&runId=${latest.reconciliationRunId}&historyView=table`)
  await page.getByRole('button', { name: '표 보기', exact: true }).click()
  const results = await api(page, `/reconciliations/${latest.reconciliationRunId}/results?page=1&size=100`)
  expect(results.summary.exceptionCount).toBe(0)
  expect(results.summary.matchedCount).toBe(results.summary.resultCount)
  const checklist = await api(page, `/validation-runs/${rerun.validationRunId}/finalize-checklist`)
  expect(checklist.passed, JSON.stringify(checklist)).toBe(true)
  await page.goto(`/validation-runs/${rerun.validationRunId}?month=2026-07`)
  await expect(page.getByRole('button', { name: '확정', exact: true })).toBeDisabled()
  await login(page, 'gaadmin', `/validation-runs/${rerun.validationRunId}?month=2026-07`)
  await expect(page.getByRole('button', { name: '확정', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '확정', exact: true }).click()
  const finalized = responseFor(page, `/validation-runs/${rerun.validationRunId}/finalize`)
  await page.getByRole('dialog').getByRole('button', { name: '확정', exact: true }).click()
  expect((await successful(finalized)).status).toBe('FINALIZED')
  await page.getByRole('dialog', { name: '확정했습니다' }).getByRole('button', { name: '확인', exact: true }).click()
  expect((await api(page, `/validation-runs/${rerun.validationRunId}`)).header.status).toBe('FINALIZED')
  expect(sql(`SELECT status FROM fgc.validation_run WHERE validation_run_id=${rerun.validationRunId}`)).toBe(
    'FINALIZED',
  )
  expect(errors).toEqual([])
  expect(writes.filter((path) => path === '/api/v1/validation-runs')).toHaveLength(2)
  expect(writes.filter((path) => path.endsWith('/execute'))).toHaveLength(2)
  expect(writes.filter((path) => path.endsWith('/finalize'))).toHaveLength(1)
  expect(writes.filter((path) => path.startsWith('/api/v1/reconciliations'))).toHaveLength(0)
  expect(writes.filter((path) => path.startsWith('/api/v1/transactions'))).toHaveLength(0)
  await info.attach('guide-completed', { body: await page.screenshot(), contentType: 'image/png' })
  await info.attach('correction-evidence', {
    body: JSON.stringify({
      pid,
      replacement,
      run,
      recon,
      revised,
      correction,
      rerun,
      latest,
      checklist,
      results,
      writes,
      errors,
    }),
    contentType: 'application/json',
  })
})
