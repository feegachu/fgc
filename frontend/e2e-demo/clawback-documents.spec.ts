import { request, type APIRequestContext, type Page, type TestInfo } from '@playwright/test'
import { createHash, randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import type { components } from '../src/lib/api/schema'
import { test, expect, login, password, responseFor, successful, sql } from './fixtures'

type Calculation = components['schemas']['Calculation']
type Document = components['schemas']['ClawbackNoticeDocumentResponse']
type Detail = components['schemas']['ClawbackCandidateDetailResponse']
const origin = 'http://127.0.0.1:15173'
const scalar = (statement: string) => Number(sql(statement))

async function session(account: string) {
  const auth = await request.newContext({
    baseURL: origin,
    extraHTTPHeaders: { Origin: origin, 'X-FGC-Client': 'web' },
  })
  try {
    const response = await auth.post('/api/v1/auth/login', { data: { loginId: account, password } })
    expect(response.status()).toBe(200)
    const token = (await response.json()).data.accessToken
    return await request.newContext({ baseURL: origin, extraHTTPHeaders: { Authorization: `Bearer ${token}` } })
  } finally {
    await auth.dispose()
  }
}
async function data<T>(client: APIRequestContext, path: string, body?: unknown, key?: string): Promise<T> {
  const response = await client.fetch(path, {
    method: body === undefined ? 'GET' : 'POST',
    data: body,
    headers: key ? { 'Idempotency-Key': key } : undefined,
  })
  expect(response.ok(), `${response.status()} ${await response.text()}`).toBe(true)
  return (await response.json()).data
}

// SQL 원천 준비는 com.fgc.e2e=418 / fgc_e2e로 검증된 일회성 DB에만 허용된다.
// 계산·발행·이력·다운로드는 응답 fixture 없이 실제 서버로 요청한다.
async function seedCandidate(client: APIRequestContext) {
  const contractNo = 'E2E-727-PDF'
  const contract = scalar(`WITH inserted AS (
    INSERT INTO fgc.insurance_contract(insurer_id,product_offering_id,contract_no,contract_date,
      agent_id,organization_id,premium_per_cycle_amount,first_premium_amount,
      monthly_equivalent_first_premium,payment_term_months)
    SELECT insurer_id,product_offering_id,'${contractNo}',DATE '2026-01-01',agent_id,organization_id,
      premium_per_cycle_amount,first_premium_amount,monthly_equivalent_first_premium,payment_term_months
    FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1 RETURNING contract_id
  ) SELECT contract_id FROM inserted`)
  const policy = scalar(`WITH inserted AS (
    INSERT INTO fgc.policy_version(policy_code,policy_name,policy_type,source_class,version_no,effective_from,status,created_by)
    VALUES ('E2E-727-POLICY','환수 PDF 실제 API 검증','CLAWBACK','GA_POLICY',1,DATE '2020-01-01','DRAFT',
      (SELECT min(user_id) FROM fgc.app_user)) RETURNING policy_version_id
  ) SELECT policy_version_id FROM inserted`)
  sql(`INSERT INTO fgc.clawback_rule(policy_version_id,commission_item_id,elapsed_month_from,elapsed_month_to,clawback_basis_code,clawback_rate_pct)
    VALUES (${policy},(SELECT commission_item_id FROM fgc.commission_item WHERE item_code='BASE_COMMISSION'),0,1199,'PAID_ATTRIBUTED_AMOUNT',50);
    UPDATE fgc.policy_version SET status='APPROVED',approved_at=now(),approved_by=(SELECT max(user_id) FROM fgc.app_user) WHERE policy_version_id=${policy};
    UPDATE fgc.policy_version SET status='ACTIVE' WHERE policy_version_id=${policy};`)
  payment(contract, '1000.4')
  const event = scalar(`WITH inserted AS (
    INSERT INTO fgc.contract_status_event(contract_id,event_seq,new_status,effective_at,received_at,source_system,source_event_key,source_version)
    VALUES (${contract},1,'LAPSED',TIMESTAMPTZ '2026-04-01T00:00:00+09:00',now(),'E2E-727','pdf-source','source-v1')
    RETURNING contract_status_event_id
  ) SELECT contract_status_event_id FROM inserted`)
  const candidate = scalar(`WITH inserted AS (
    INSERT INTO fgc.clawback_candidate(contract_id,trigger_type,status_event_id,source_version,event_date)
    SELECT e.contract_id,'STATUS_EVENT',e.contract_status_event_id,md5(to_jsonb(e)::text),CAST(e.effective_at AT TIME ZONE 'Asia/Seoul' AS date)
    FROM fgc.contract_status_event e WHERE e.contract_status_event_id=${event} RETURNING candidate_id
  ) SELECT candidate_id FROM inserted`)
  const detail = await data<Detail>(client, `/api/v1/clawbacks/candidates/${candidate}`)
  return { contract, contractNo, candidate, policy, detail }
}
async function prepare(client: APIRequestContext) {
  const seed = await seedCandidate(client)
  const calculation = await data<Calculation>(
    client,
    `/api/v1/clawbacks/candidates/${seed.candidate}/calculations`,
    {
      expectedSourceVersion: seed.detail.candidate!.sourceVersion,
      policyVersionId: seed.policy,
      reason: '실제 PDF 금액·판·원천 검증',
    },
    randomUUID(),
  )
  expect(calculation.amount).toBe(500)
  return { ...seed, caseId: calculation.clawbackCaseId!, calculation }
}
function payment(contract: number, amount: string) {
  const transaction = scalar(`WITH inserted AS (
    INSERT INTO fgc.commission_transaction(payment_stage,source_type,source_business_key,insurer_id,recipient_agent_id,
      commission_item_id,settlement_month,paid_on,amount,cashflow_type,installment_no)
    SELECT 'GA_TO_FC','GA_MANUAL_PAYMENT','${randomUUID()}',insurer_id,agent_id,
      (SELECT commission_item_id FROM fgc.commission_item WHERE item_code='BASE_COMMISSION'),DATE '2026-03-01',DATE '2026-03-01',${amount},'PAYMENT',1
    FROM fgc.insurance_contract WHERE contract_id=${contract} RETURNING commission_transaction_id
  ) SELECT commission_transaction_id FROM inserted`)
  sql(`INSERT INTO fgc.transaction_attribution(commission_transaction_id,contract_id,agent_id,attribution_date,attribution_month,
      attributed_amount,inclusion_status_snapshot,attribution_method)
    SELECT ${transaction},contract_id,agent_id,DATE '2026-03-01',DATE '2026-03-01',${amount},'INCLUDED','DIRECT'
    FROM fgc.insurance_contract WHERE contract_id=${contract};
    UPDATE fgc.commission_transaction SET status='CONFIRMED' WHERE commission_transaction_id=${transaction};`)
}
const caseState = (caseId: number) =>
  sql(`SELECT row_to_json(c) FROM (
  SELECT status,notice_date,confirmed_amount,calculated_amount,updated_at FROM fgc.clawback_case WHERE clawback_case_id=${caseId}
) c`)
async function capture(page: Page, info: TestInfo, filename: string) {
  await page.screenshot({ path: info.outputPath(filename), fullPage: true })
}
async function download(client: APIRequestContext, document: Document, info: TestInfo) {
  const response = await client.get(document.downloadPath!)
  expect(response.ok()).toBe(true)
  const bytes = await response.body()
  expect(bytes.subarray(0, 5).toString()).toBe('%PDF-')
  await writeFile(info.outputPath(`edition-${document.version}.pdf`), bytes)
  return createHash('sha256').update(bytes).digest('hex')
}
async function openCalculation(page: Page) {
  return page.getByRole('region', { name: '환수 계산', exact: true })
}
async function closeViewer(page: Page) {
  const viewer = page.getByRole('dialog', { name: '환수 문서뷰어' })
  await expect(viewer.locator('iframe')).toHaveAttribute('src', /^blob:/)
  await viewer.getByRole('button', { name: '창 닫기', exact: true }).click()
}

test('화면에서 정책 선택·사유 입력·환수액 계산 후 PDF를 생성하고 다운로드한다', async ({ page }, info) => {
  const admin = await session('admin')
  try {
    const seed = await seedCandidate(admin)
    expect(seed.detail.latestCalculation).toBeNull()
    await login(page, 'settle01', `/clawbacks?month=2026-04&contractNo=${seed.contractNo}`)
    await page.getByRole('button', { name: `${seed.contractNo} 환수 상세 보기 · 후보 ${seed.candidate}`, exact: true }).click()
    const calc = await openCalculation(page)
    const pdf = calc
    await expect(page.getByRole('button', { name: '환수액 계산', exact: true })).toBeDisabled()
    await expect(pdf.getByRole('button', { name: 'PDF 출력하기', exact: true })).toHaveCount(0)
    await calc.getByRole('radio', { name: /환수 PDF 실제 API 검증 v1/ }).check()
    await calc.getByLabel(/계산 사유/).fill('등록된 환수 정책으로 계산 후 PDF 발행')
    await expect(page.getByRole('button', { name: '환수액 계산', exact: true })).toBeEnabled()
    const calculated = responseFor(page, `/clawbacks/candidates/${seed.candidate}/calculations`)
    await page.getByRole('button', { name: '환수액 계산', exact: true }).click()
    const result = (await successful(calculated)) as Calculation
    expect(result.amount).toBe(500)
    expect(result.calculationVersion).toBe(1)
    await expect(calc.locator('.claw-calculation-total')).toContainText('500원')
    await expect(calc.getByLabel(/계산 사유/)).toHaveValue('')
    await expect(pdf.getByRole('button', { name: 'PDF 출력하기', exact: true })).toBeEnabled()
    const issued = responseFor(page, `/clawbacks/${result.clawbackCaseId}/notice-documents`)
    await pdf.getByRole('button', { name: 'PDF 출력하기', exact: true }).click()
    const document = (await successful(issued)) as Document
    expect(document.calculationVersion).toBe(result.calculationVersion)
    await closeViewer(page)
    expect(await download(admin, document, info)).toBe(document.sha256)
    await capture(page, info, 'real-ui-calculation-to-pdf.png')
    await calc.screenshot({ path: info.outputPath('real-ui-calculation.png') })
  } finally {
    await admin.dispose()
  }
})

test('CT-W02 실제 API: 응답 유실·동일 키 재시도·새 발행본·원본 해시·상태 불변', async ({ page }, info) => {
  const admin = await session('admin')
  try {
    const seed = await prepare(admin)
    const before = caseState(seed.caseId)
    const path = `/api/v1/clawbacks/${seed.caseId}/notice-documents`
    const writes: { key?: string; body: unknown }[] = []
    let lost = false
    await page.route(`**${path}`, async (route) => {
      if (route.request().method() !== 'POST') return route.continue()
      writes.push({ key: route.request().headers()['idempotency-key'], body: route.request().postDataJSON() })
      if (!lost) {
        lost = true
        const committed = await route.fetch()
        expect(committed.status()).toBe(201)
        return route.abort('failed') // 실제 서버 커밋 뒤 응답만 유실시킨다.
      }
      return route.continue()
    })
    await login(page, 'settle01', `/clawbacks?month=2026-04&contractNo=${seed.contractNo}`)
    await page.getByRole('button', { name: `${seed.contractNo} 환수 상세 보기 · 후보 ${seed.candidate}`, exact: true }).click()
    const panel = await openCalculation(page)
    await expect(panel).toContainText('500원')
    await panel.getByRole('button', { name: 'PDF 출력하기', exact: true }).click()
    const replay = responseFor(page, `/clawbacks/${seed.caseId}/notice-documents`)
    await expect(panel.getByRole('alert')).toContainText('다시 시도')
    await panel.getByRole('button', { name: 'PDF 출력하기', exact: true }).click()
    const first = (await successful(replay)) as Document
    expect(first.version).toBe(1)
    expect(writes[0]).toEqual(writes[1])
    expect(writes[0].body).toEqual({ calculationVersion: 1, expectedSourceVersion: seed.calculation.sourceVersion })
    expect(
      sql(
        `SELECT count(*) FROM fgc.generated_document WHERE source_entity_id=${seed.caseId} AND document_kind='CLAWBACK_NOTICE'`,
      ),
    ).toBe('1')
    expect(await download(admin, first, info)).toBe(first.sha256)
    await closeViewer(page)
    // The current UI reopens its saved PDF. New editions remain an API capability.
    const second = await data<Document>(admin, path,
      { calculationVersion: 1, expectedSourceVersion: seed.calculation.sourceVersion }, randomUUID())
    expect(second.version).toBe(2)
    expect(second.documentNo).toBe(first.documentNo)
    expect(await download(admin, first, info)).toBe(first.sha256)
    expect(await download(admin, second, info)).toBe(second.sha256)
    expect(caseState(seed.caseId)).toBe(before)
    const snapshot = JSON.parse(
      sql(
        `SELECT result_snapshot FROM fgc.clawback_calculation WHERE clawback_case_id=${seed.caseId} AND version_no=1`,
      ),
    )
    expect(snapshot.amount).toBe(500)
    expect(snapshot.lines[0].basisAmount).toBe(1000)
    expect(snapshot.lines[0].appliedRatePct).toBe(50)
    expect(snapshot.lines[0].clawbackAmount).toBe(500)
    await info.attach('actual-api-snapshot', {
      body: JSON.stringify({ calculation: seed.calculation, first, second, state: JSON.parse(before) }, null, 2),
      contentType: 'application/json',
    })
    await capture(page, info, 'real-api-history.png')
  } finally {
    await admin.dispose()
  }
})

test('CT-W02 실제 API: 원천 변경 409·재계산·과거판 보존·COMPLIANCE·GA_ADMIN·AGENT 범위', async ({ page }, info) => {
  const admin = await session('admin')
  try {
    const seed = await prepare(admin)
    const path = `/api/v1/clawbacks/${seed.caseId}/notice-documents`
    const first = await data<Document>(
      admin,
      path,
      { calculationVersion: 1, expectedSourceVersion: seed.calculation.sourceVersion },
      randomUUID(),
    )
    await login(page, 'settle01', `/clawbacks?month=2026-04&contractNo=${seed.contractNo}`)
    await page.getByRole('button', { name: `${seed.contractNo} 환수 상세 보기 · 후보 ${seed.candidate}`, exact: true }).click()
    const panel = await openCalculation(page)
    await expect(panel.getByRole('button', { name: 'PDF 출력하기', exact: true })).toBeEnabled()
    payment(seed.contract, '500')
    const stale = await admin.post(path, {
      data: { calculationVersion: 1, expectedSourceVersion: seed.calculation.sourceVersion },
      headers: { 'Idempotency-Key': randomUUID() },
    })
    expect(stale.status()).toBe(409)
    await page.reload()
    await page.getByRole('button', { name: `${seed.contractNo} 환수 상세 보기 · 후보 ${seed.candidate}`, exact: true }).click()
    await openCalculation(page)
    await expect(panel.getByRole('button', { name: 'PDF 출력하기', exact: true })).toBeDisabled()
    expect(await download(admin, first, info)).toBe(first.sha256)
    const current = await data<Detail>(admin, `/api/v1/clawbacks/candidates/${seed.candidate}`)
    const recalculated = await data<Calculation>(
      admin,
      `/api/v1/clawbacks/candidates/${seed.candidate}/calculations`,
      {
        expectedSourceVersion: current.candidate!.sourceVersion,
        policyVersionId: seed.policy,
        reason: '원천 변경 후 다시 계산',
      },
      randomUUID(),
    )
    expect(recalculated.amount).toBe(750)
    expect(recalculated.calculationVersion).toBe(2)
    const state = caseState(seed.caseId)
    await login(page, 'gaadmin', `/clawbacks?month=2026-04&contractNo=${seed.contractNo}`)
    await page.getByRole('button', { name: `${seed.contractNo} 환수 상세 보기 · 후보 ${seed.candidate}`, exact: true }).click()
    await openCalculation(page)
    const secondResponse = responseFor(page, `/clawbacks/${seed.caseId}/notice-documents`)
    await panel.getByRole('button', { name: 'PDF 출력하기', exact: true }).click()
    const second = (await successful(secondResponse)) as Document
    expect(second.version).toBe(2)
    expect(second.calculationVersion).toBe(2)
    await closeViewer(page)
    expect(await download(admin, first, info)).toBe(first.sha256)
    expect(caseState(seed.caseId)).toBe(state)
    await login(page, 'audit01', `/clawbacks?month=2026-04&contractNo=${seed.contractNo}`)
    await page.getByRole('button', { name: `${seed.contractNo} 환수 상세 보기 · 후보 ${seed.candidate}`, exact: true }).click()
    await openCalculation(page)
    await expect(panel.getByRole('button', { name: 'PDF 출력하기', exact: true })).toBeEnabled()
    expect(await download(admin, second, info)).toBe(second.sha256)
    await capture(page, info, 'real-api-compliance.png')
    const reader = await session('audit01')
    try {
      expect(await download(reader, second, info)).toBe(second.sha256)
      expect(
        (
          await reader.post(path, {
            data: { calculationVersion: 2, expectedSourceVersion: current.candidate!.sourceVersion },
            headers: { 'Idempotency-Key': randomUUID() },
          })
        ).status(),
      ).toBe(403)
      expect((await reader.get(`/api/v1/documents/${first.documentNo}.pdf?version=999`)).status()).toBe(404)
    } finally {
      await reader.dispose()
    }
    sql(
      "UPDATE fgc.app_user SET account_status='ACTIVE',password_hash=(SELECT password_hash FROM fgc.app_user WHERE login_id='settle01') WHERE login_id='agent01'",
    )
    const agent = await session('agent01')
    try {
      expect((await agent.get(path)).status()).toBe(403)
      expect(
        (
          await agent.post(path, {
            data: { calculationVersion: 2, expectedSourceVersion: current.candidate!.sourceVersion },
            headers: { 'Idempotency-Key': randomUUID() },
          })
        ).status(),
      ).toBe(403)
      expect((await agent.get(first.downloadPath!)).status()).toBe(403)
      expect((await agent.get(`/api/v1/clawbacks/candidates/${seed.candidate}`)).status()).toBe(403)
    } finally {
      await agent.dispose()
    }
    expect(caseState(seed.caseId)).toBe(state)
    expect(sql(`SELECT count(*) FROM fgc.clawback_notice_delivery WHERE clawback_case_id=${seed.caseId}`)).toBe('0')
    await info.attach('actual-role-and-version-results', {
      body: JSON.stringify({ first, second, recalculated, state: JSON.parse(state) }, null, 2),
      contentType: 'application/json',
    })
  } finally {
    await admin.dispose()
  }
})
