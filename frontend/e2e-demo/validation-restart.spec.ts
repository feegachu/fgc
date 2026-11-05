import { randomUUID } from 'node:crypto'
import type { Page } from '@playwright/test'
import { test, expect, login, api, responseFor, successful, sql, password } from './fixtures'

test.use({ video: 'off' })

function positiveId(value: unknown): number {
  expect(Number.isSafeInteger(value)).toBe(true)
  expect(Number(value)).toBeGreaterThan(0)
  return Number(value)
}

// sql() checks the disposable E2E database label/name/port before every command.
// The trigger is scoped to this run and is always removed before attempting restart.
async function failedMonthly(page: Page) {
  await login(page, 'settle01', '/validation-runs?month=2026-07')
  const contracts = await api(page, '/contracts?contractNo=FGC-FGL01-202607-0002&page=1&size=20')
  expect(contracts.content).toHaveLength(1)
  const contract = await api(page, `/contracts/${contracts.content[0].contractId}`)
  const items = await api(page, '/base/commission-items?asOf=2026-07-01')
  const item = items.find((entry: { itemCode: string }) => entry.itemCode === 'BASE_COMMISSION')
  expect(item).toBeTruthy()
  const policy = positiveId(Number(sql("SELECT policy_version_id FROM fgc.policy_version WHERE policy_code='REG-CAP-GA-2026-V1' AND status='ACTIVE'")))
  const payment = await api(page, '/transactions', {
    sourceType: 'GA_MANUAL_PAYMENT', sourceBusinessKey: `E2E-630-${randomUUID()}`,
    contractId: contract.contractId, agentId: contract.agentId, commissionItemId: item.commissionItemId,
    amount: 20, settlementMonth: '2026-07-01', cashflowType: 'PAYMENT',
    scheduledPaymentDate: '2026-07-31', paymentStage: 'GA_TO_FC', allocationPolicyVersion: policy,
    attributions: [{ contractId: contract.contractId, attributionDate: '2026-07-31', amount: 20,
      inclusionDecisionStatus: 'INCLUDED', exclusionType: 'NONE', inclusionDecisionReason: 'E2E-630',
      allocationBasis: 'DIRECT', evidenceRef: 'E2E-630', attributionMethod: 'DIRECT' }],
    evidenceRef: 'E2E-630', note: 'E2E-630 isolated restart acceptance',
  })
  const paymentId = positiveId(payment.commissionTransactionId)
  expect((await api(page, `/transactions/${paymentId}/precheck`, {})).confirmable).toBe(true)
  await api(page, `/transactions/${paymentId}/confirm`, {})
  expect((await api(page, `/transactions/${paymentId}`)).status).toBe('CONFIRMED')
  const created = responseFor(page, '/validation-runs')
  await page.getByRole('button', { name: '실행 생성' }).click()
  const id = positiveId((await successful(created)).validationRunId)
  try {
    sql(`CREATE FUNCTION fgc.e2e_630_fail_journal() RETURNS trigger LANGUAGE plpgsql AS $fault$
      BEGIN
        IF NEW.validation_run_id = ${id} THEN
          RAISE EXCEPTION 'E2E-630 intended journal failure';
        END IF;
        RETURN NEW;
      END $fault$;
      CREATE TRIGGER e2e_630_fail_journal BEFORE INSERT ON fgc.journal_header
      FOR EACH ROW EXECUTE FUNCTION fgc.e2e_630_fail_journal();`)
    const execution = responseFor(page, `/validation-runs/${id}/execute`)
    await page.getByRole('button', { name: '실행', exact: true }).click()
    await successful(execution)
    await expect.poll(async () => (await api(page, `/validation-runs/${id}/progress`)).status,
      { timeout: 120_000 }).toBe('FAILED')
  } finally {
    sql('DROP TRIGGER IF EXISTS e2e_630_fail_journal ON fgc.journal_header; DROP FUNCTION IF EXISTS fgc.e2e_630_fail_journal();')
  }
  const progress = await api(page, `/validation-runs/${id}/progress`)
  expect(progress.failedStep).toBe(6)
  const state = await api(page, `/validation-runs/${id}/restartability`)
  expect(state.allowed).toBe(true)
  expect(state.failedStep).toBe(6)
  positiveId(state.lastFailedBatchExecutionId)
  expect(state.expectedStateVersion).toBeTruthy()
  return { id, paymentId, state }
}

test('FUN-045 실제 서버: 6단계 실패 → 사유 입력 → 동일 실행 재시작 → 완료·이력·원장 보존', async ({ page }, info) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const { id, paymentId, state } = await failedMonthly(page)
  const panel = page.getByRole('region', { name: '실패 지점 재시작', exact: true })
  const restart = panel.getByRole('button', { name: '실패 지점부터 재시작' })
  await expect(restart).toBeEnabled()
  const steps = page.getByRole('list', { name: '월 통합검증 10단계 진행 상태' }).getByRole('listitem')
  await expect(steps.nth(5)).toContainText('실패')
  await info.attach('actual-server-failed-step', { body: await page.screenshot(), contentType: 'image/png' })
  await restart.click()
  const dialog = page.getByRole('dialog', { name: '실패 지점 재시작 확인' })
  const submit = dialog.getByRole('button', { name: '재시작 요청' })
  await expect(submit).toBeDisabled()
  await dialog.getByRole('textbox', { name: '재시작 사유' }).fill('   ')
  await expect(submit).toBeDisabled()
  const reason = '원장 저장 장애 제거 후 체크포인트 재시작'
  await dialog.getByRole('textbox', { name: '재시작 사유' }).fill(`  ${reason}  `)
  const accepted = responseFor(page, `/validation-runs/${id}/restart`)
  await submit.click()
  const response = await accepted
  expect(response.status()).toBe(202)
  expect(response.request().postDataJSON()).toEqual({
    expectedBatchExecutionId: state.lastFailedBatchExecutionId,
    expectedStateVersion: state.expectedStateVersion, reason,
  })
  expect(response.request().headers()['idempotency-key']).toMatch(/^[a-f0-9-]{36}$/i)
  await expect.poll(async () => (await api(page, `/validation-runs/${id}/progress`)).status,
    { timeout: 120_000 }).toBe('COMPLETED')
  const history = page.getByRole('table', { name: '재시도 이력' })
  await expect(history).toContainText(reason)
  await expect(history).toContainText('6단계')
  await expect(history).toContainText('완료')
  await expect(restart).toHaveCount(0)
  await page.reload()
  await expect(history).toContainText(reason)
  await expect(history).toContainText('완료')
  await expect(restart).toHaveCount(0)
  const retries = await api(page, `/validation-runs/${id}/retries`)
  expect(retries).toHaveLength(1)
  expect(retries[0].state).toBe('SUCCEEDED')
  const newExecution = positiveId(retries[0].batchExecutionId)
  expect(newExecution).not.toBe(state.lastFailedBatchExecutionId)
  expect(sql(`SELECT count(DISTINCT job_instance_id) FROM fgc.batch_job_execution
    WHERE job_execution_id IN (${state.lastFailedBatchExecutionId},${newExecution})`)).toBe('1')
  expect(sql(`SELECT count(*) FROM fgc.journal_header WHERE validation_run_id=${id}
    AND source_entity_type='COMMISSION_TRANSACTION' AND source_entity_id='${paymentId}' AND status='POSTED'`)).toBe('1')
  expect(sql(`SELECT count(*) FROM (SELECT journal_type,source_entity_type,source_entity_id,revision_no
    FROM fgc.journal_header WHERE validation_run_id=${id}
    GROUP BY journal_type,source_entity_type,source_entity_id,revision_no HAVING count(*)>1) duplicates`)).toBe('0')
  expect(sql(`SELECT count(*) FROM (SELECT h.journal_header_id FROM fgc.journal_header h
    JOIN fgc.journal_line l USING(journal_header_id) WHERE h.validation_run_id=${id}
    GROUP BY h.journal_header_id HAVING sum(l.debit_amount)<>sum(l.credit_amount)) imbalance`)).toBe('0')
  expect((await api(page, `/validation-runs/${id}`)).header.status).toBe('COMPLETED')
  expect(errors).toEqual([])
  await info.attach('actual-server-completed-restart', { body: await page.screenshot(), contentType: 'image/png' })
  await info.attach('restart-result', { body: JSON.stringify({ id, paymentId, retries }), contentType: 'application/json' })
})

for (const account of ['audit01', 'gaadmin']) {
  test(`FUN-045 실제 서버: ${account} 읽기 역할은 실패 이력만 조회하고 재시작 불가`, async ({ page, request }, info) => {
    const { id, state } = await failedMonthly(page)
    await login(page, account, `/validation-runs/${id}?month=2026-07`)
    const panel = page.getByRole('region', { name: '실패 지점 재시작', exact: true })
    await expect(panel.getByRole('button', { name: '실패 지점부터 재시작' })).toBeDisabled()
    await expect(panel).toContainText('재시작은 정산담당자와 시스템관리자만 할 수 있습니다.')
    expect(await api(page, `/validation-runs/${id}/retries`)).toEqual([])
    await info.attach(`actual-server-${account}-read-only`, { body: await page.screenshot(), contentType: 'image/png' })
    // Authenticate only after the browser assertions; duplicate login may revoke its session.
    const origin = new URL(page.url()).origin
    const auth = await request.post(`${origin}/api/v1/auth/login`, {
      data: { loginId: account, password }, headers: { Origin: origin, 'X-FGC-Client': 'web' },
    })
    expect(auth.ok()).toBe(true)
    const token = (await auth.json()).data.accessToken
    const denied = await request.post(`${origin}/api/v1/validation-runs/${id}/restart`, {
      headers: { Authorization: `Bearer ${token}`, Origin: origin, 'X-FGC-Client': 'web', 'Idempotency-Key': randomUUID() },
      data: { expectedBatchExecutionId: state.lastFailedBatchExecutionId,
        expectedStateVersion: state.expectedStateVersion, reason: '권한 거부 시험' },
    })
    expect(denied.status()).toBe(403)
    expect(sql(`SELECT count(*) FROM fgc.validation_run_retry WHERE validation_run_id=${id}`)).toBe('0')
  })
}


test('FUN-045 실제 서버: 빈 사유 400·지난 상태 409는 재시도 행을 만들지 않는다', async ({ page, request }) => {
  const { id, state } = await failedMonthly(page)
  const origin = new URL(page.url()).origin
  const auth = await request.post(`${origin}/api/v1/auth/login`, {
    data: { loginId: 'admin', password }, headers: { Origin: origin, 'X-FGC-Client': 'web' },
  })
  expect(auth.ok()).toBe(true)
  const token = (await auth.json()).data.accessToken
  for (const probe of [
    { reason: '   ', version: state.expectedStateVersion, status: 400 },
    { reason: '변경 전 상태로 요청', version: `stale-${state.expectedStateVersion}`, status: 409 },
  ]) {
    const rejected = await request.post(`${origin}/api/v1/validation-runs/${id}/restart`, {
      headers: { Authorization: `Bearer ${token}`, Origin: origin, 'X-FGC-Client': 'web', 'Idempotency-Key': randomUUID() },
      data: { expectedBatchExecutionId: state.lastFailedBatchExecutionId,
        expectedStateVersion: probe.version, reason: probe.reason },
    })
    expect(rejected.status()).toBe(probe.status)
    expect(sql(`SELECT count(*) FROM fgc.validation_run_retry WHERE validation_run_id=${id}`)).toBe('0')
    expect(sql(`SELECT status FROM fgc.validation_run WHERE validation_run_id=${id}`)).toBe('FAILED')
  }
})
