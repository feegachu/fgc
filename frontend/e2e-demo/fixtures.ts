import { test as base, expect, request, type Page, type Response } from '@playwright/test'
import { resetDatabase, sql } from '../scripts/e2e-db.mjs'
import { readFileSync, writeFileSync } from 'node:fs'

export const test = base.extend<{ demo: void }>({
  demo: [
    async ({ baseURL }, runScenario, info) => {
      expect(baseURL).toBe('http://127.0.0.1:15173/app/')
      resetDatabase()
      try {
        await runScenario()
        if (info.status === 'passed') {
          const result = JSON.parse(
            sql(`SELECT json_build_object(
          'contracts', (SELECT count(*) FROM fgc.insurance_contract),
          'payments', (SELECT count(*) FROM fgc.commission_transaction),
          'confirmedAmount', (SELECT coalesce(sum(amount),0) FROM fgc.commission_transaction WHERE status='CONFIRMED'),
          'journals', (SELECT count(*) FROM fgc.journal_header),
          'reversals', (SELECT count(*) FROM fgc.journal_header WHERE journal_type='REVERSAL'),
          'runs', (SELECT count(*) FROM fgc.validation_run),
          'finalized', (SELECT count(*) FROM fgc.validation_run WHERE status='FINALIZED'),
          'targets', (SELECT count(*) FROM fgc.validation_target))`),
          )
          expect(sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory'")).toBe('0')
          const outcomes = JSON.parse(readFileSync('e2e-artifacts/outcomes.json', 'utf8'))
          outcomes[info.title] = result
          writeFileSync('e2e-artifacts/outcomes.json', JSON.stringify(outcomes, null, 2))
          await info.attach('database-outcome', {
            body: JSON.stringify(result, null, 2),
            contentType: 'application/json',
          })
        }
      } finally {
        resetDatabase()
      }
    },
    { auto: true },
  ],
})
export { expect, sql }
export const backend = 'http://127.0.0.1:18081'
export const password = process.env.FGC_E2E_PASSWORD ?? 'fgc1234!' // checked-in demo accounts only

export async function login(page: Page, account = 'settle01', route = '/contracts?month=2026-07') {
  await page.context().clearCookies()
  await page.goto(`login?redirect=${encodeURIComponent(route)}`)
  await page.getByLabel(/^아이디/).fill(account)
  await page.getByLabel(/^비밀번호\s*\*?$/).fill(password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login/)
}
export async function legacyLogin(page: Page, account = 'settle01') {
  await page.goto(`${backend}/login`)
  await page.locator('input[name=username]').fill(account)
  await page.locator('input[name=password]').fill(password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login/)
}
export async function successful(response: Promise<Response>) {
  const result = await response
  expect(result.ok(), `${result.status()} ${result.url()} ${await result.text()}`).toBe(true)
  return (await result.json()).data
}
export function responseFor(page: Page, path: string, method = 'POST') {
  return page.waitForResponse(
    (response) => new URL(response.url()).pathname === `/api/v1${path}` && response.request().method() === method,
  )
}
export async function api(page: Page, path: string, body?: unknown) {
  // The browser's own access token is intentionally not exposed. Setup requests use a separate demo admin session.
  const origin = new URL(page.url()).origin
  const client = await request.newContext({
    baseURL: origin,
    extraHTTPHeaders: { Origin: origin, 'X-FGC-Client': 'web' },
  })
  try {
    const auth = await client.post('/api/v1/auth/login', { data: { loginId: 'admin', password } })
    expect(auth.ok(), await auth.text()).toBe(true)
    const token = (await auth.json()).data.accessToken
    const result = await client.fetch(`/api/v1${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      data: body,
      headers: { Authorization: `Bearer ${token}` },
    })
    expect(result.ok(), await result.text()).toBe(true)
    return (await result.json()).data
  } finally {
    await client.dispose()
  }
}
export async function completeMonthly(page: Page, month = '2026-07') {
  const run = await api(page, '/validation-runs', { validationMonth: month, runType: 'MONTHLY' })
  await api(page, `/validation-runs/${run.validationRunId}/execute`, {})
  await expect
    .poll(async () => (await api(page, `/validation-runs/${run.validationRunId}/progress`)).status, {
      timeout: 120_000,
      message: 'Monthly batch must complete before the scenario can start',
    })
    .toBe('COMPLETED')
  return run.validationRunId as number
}
