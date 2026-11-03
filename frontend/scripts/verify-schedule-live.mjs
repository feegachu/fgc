import { chromium, expect } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { execFileSync } from 'node:child_process'

// 실서버 전용 데이터에서만 실행. 재생성은 명시적인 환경변수로 허용한다.
const origin = process.env.FGC_E2E_ORIGIN
const scheduleId = process.env.FGC_E2E_SCHEDULE_ID
const contractId = process.env.FGC_E2E_CONTRACT_ID
const loginId = process.env.FGC_E2E_LOGIN_ID
const password = process.env.FGC_E2E_PASSWORD
if (!origin || (!scheduleId && !contractId) || !loginId || !password) throw Error('FGC_E2E_ORIGIN, SCHEDULE_ID(or CONTRACT_ID), LOGIN_ID, PASSWORD are required')
if (!scheduleId && process.env.FGC_E2E_REGENERATE !== 'true') throw Error('CONTRACT_ID requires FGC_E2E_REGENERATE=true for initial generation')
const output = resolve(process.env.FGC_E2E_OUTPUT ?? 'test-results/schedule-live')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, ...(process.env.FGC_BROWSER_EXECUTABLE ? { executablePath: process.env.FGC_BROWSER_EXECUTABLE } : {}) })
const page = await browser.newPage({ viewport: { width: 1600, height: 1100 }, acceptDownloads: true })
page.setDefaultTimeout(15000)
const errors = []
page.on('pageerror', error => errors.push(error.message))
const findings = []
try {
  await page.goto(`${origin}/login?redirect=${encodeURIComponent(scheduleId ? `/schedules/${scheduleId}?month=2026-07` : `/contracts/${contractId}?tab=schedules&month=2026-07`)}`)
  await page.getByRole('textbox', { name: '아이디', exact: true }).fill(loginId)
  await page.getByRole('textbox', { name: '비밀번호', exact: true }).fill(password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.getByRole('heading', { name: scheduleId ? '예상 스케줄 상세' : '보험계약 상세', exact: true })).toBeVisible()
  const form = page.getByRole('region', { name: 'GA_TO_FC 7년 예정 생성', exact: true })
  if (contractId) {
    const missing = page.getByRole('region', { name: 'INSURER_TO_GA 7년 예정 생성', exact: true })
    await expect(missing.getByRole('button', { name: '7년 예정 생성', exact: true })).toBeDisabled()
    await expect(missing.getByRole('status')).toContainText('RULESET_MISSING')
    findings.push('정책 누락 지급단계는 예정 생성 차단')
  }
  const initialButton = scheduleId ? '7년 예정 새 버전 만들기' : '7년 예정 생성'
  const next = page.getByRole('button', { name: '다음', exact: true })
  if (process.env.FGC_E2E_REGENERATE === 'true') {
    await expect(form.getByRole('button', { name: initialButton, exact: true })).toBeDisabled()
    await form.getByRole('button', { name: '도움말', exact: true }).click()
    await expect(next).toBeDisabled()
    await form.getByRole('textbox', { name: '생성 사유' }).fill('Chrome 실서버 생성 도움말 검증')
    await expect(next).toBeEnabled()
    await next.click()
    await expect(next).toBeDisabled()
    const before = page.url()
    await form.getByRole('button', { name: initialButton, exact: true }).click()
    await expect(next).toBeEnabled()
    expect(page.url()).toBe(before)
    await expect(form.getByText('progress_activity', { exact: true })).toHaveCount(0)
    await next.click()
    await expect(page.getByText('서버 저장 확인 · 가이드 완료', { exact: true })).toBeVisible()
    await page.screenshot({ path: resolve(output, 'guide-complete.png') })
    await page.getByRole('button', { name: '안내 마치기', exact: true }).click()
    await expect(page).not.toHaveURL(before)
    await expect(page.getByRole('heading', { name: /스케줄 헤더/ })).toBeVisible()
    findings.push('사유 누락 차단, 서버 성공→가이드 완료→사용자 종료 후 이동')

    // 서버 쓰기는 실제로 완료시키고 응답만 유실시켜 동일 멱등키 재시도를 검증한다.
    const attempts = []
    let storedId
    await page.route('**/api/v1/schedules/*/regenerate', async route => {
      attempts.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() })
      if (attempts.length === 1) {
        const response = await route.fetch()
        expect(response.status()).toBe(200)
        storedId = (await response.json()).data.scheduleHeaderId
        await route.abort('failed')
      } else await route.continue()
    })
    await form.getByRole('textbox', { name: '생성 사유' }).fill('Chrome 응답 유실 멱등 재시도 검증')
    await form.getByRole('button', { name: '7년 예정 새 버전 만들기', exact: true }).click()
    await expect(page.getByRole('button', { name: '생성 결과 다시 확인' })).toBeVisible()
    await expect(form.getByRole('textbox', { name: '생성 사유' })).toBeDisabled()
    await page.getByRole('button', { name: '생성 결과 다시 확인' }).click()
    await expect(page).toHaveURL(new RegExp(`/schedules/${storedId}(?:\\?|$)`))
    expect(attempts).toHaveLength(2)
    expect(attempts[1]).toEqual(attempts[0])
    await page.unroute('**/api/v1/schedules/*/regenerate')
    findings.push('실제 서버 저장 후 응답 유실→동일 키·본문 재시도→동일 헤더 복귀')
  }
  const table = page.getByRole('table', { name: '회차별 예상 지급 금액', exact: true })
  const longRows = table.getByRole('row').filter({ hasText: '38 검증 장기유지 수수료' })
  await expect(longRows).toHaveCount(24)
  const rows = await longRows.evaluateAll(rs => rs.map(r => Array.from(r.querySelectorAll('td'), c => c.textContent.trim())))
  for (let i = 0; i < 24; i++) {
    expect(rows[i][1]).toBe(String(i + 61))
    expect(rows[i][7]).toBe('100,000원')
    expect(rows[i][9]).toBe('12,000원')
    expect(rows[i][10]).toBe('12,000원')
  }
  await expect(table.getByRole('row').filter({ hasText: '합계 (108줄)' })).toContainText('2,304,000원')
  await expect(page.getByRole('button', { name: '확정', exact: true })).toBeDisabled()
  const currentUrl = page.url()
  const versions = page.getByRole('table', { name: '같은 계약의 스케줄 버전 이력' })
  const oldVersion = versions.getByRole('link', { name: 'v1', exact: true })
  if (await oldVersion.count() && await oldVersion.getAttribute('href') !== new URL(currentUrl).pathname) {
    await oldVersion.click()
    await expect(page.getByRole('heading', { name: /스케줄 헤더/ })).toBeVisible()
    await expect(page.getByRole('button', { name: '7년 예정 새 버전 만들기', exact: true })).toHaveCount(0)
    await page.goto(currentUrl)
    await expect(longRows).toHaveCount(24)
    findings.push('구버전 조회는 유지하고 구버전에서 예정 재생성 차단')
  }
  const downloadPromise = page.waitForEvent('download', { timeout: 15000 })
  await page.getByRole('button', { name: 'CSV 내보내기', exact: true }).click()
  const download = await downloadPromise
  await download.saveAs(resolve(output, 'schedule.csv'))
  expect(await download.failure()).toBeNull()
  execFileSync('python3', ['-c', `import csv,sys
from decimal import Decimal
with open(sys.argv[1],encoding='utf-8-sig',newline='') as f: rows=list(csv.DictReader(f))
fixed=[r for r in rows if r['계산방식']=='FIXED']
assert len(rows)==108 and len(fixed)==24
assert [int(r['회차']) for r in fixed]==list(range(61,85))
assert all(Decimal(r['기준금액'])==100000 and Decimal(r['정액'])==12000 and Decimal(r['예상금액'])==12000 for r in fixed)
assert all(r['정액']=='' for r in rows if r['계산방식']=='RATE')
assert sum(Decimal(r['예상금액']) for r in rows)==2304000
`, resolve(output, 'schedule.csv')])
  await longRows.last().scrollIntoViewIfNeeded()
  await page.screenshot({ path: resolve(output, 'schedule-result.png') })
  expect(errors).toEqual([])
  findings.push('61~84차월 24행, 기준금액·정액·108행 합계, 확정 차단, 실제 CSV 다운로드')
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ url: page.url(), findings, errors }, null, 2))
  console.log(JSON.stringify({ url: page.url(), findings, output }))
} catch (error) {
  await page.screenshot({ path: resolve(output, 'failure.png') }).catch(() => {})
  await writeFile(resolve(output, 'failure.txt'), `${error}\n${await page.locator('body').innerText()}`).catch(() => {})
  throw error
} finally { await browser.close() }
