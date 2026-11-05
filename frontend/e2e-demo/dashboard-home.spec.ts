import { expect, login, sql, test } from './fixtures'

/** 시드에 미처리 예외가 없을 수 있어 건수를 직접 넣는다. fixtures 가 시험마다 DB 를 되돌린다. */
function insertOpenExceptions(count: number) {
  sql(`INSERT INTO fgc.exception_case(exception_key, exception_type, severity, status, source_entity_type, source_entity_id, title, contract_id)
       SELECT 'E2E-HOME:' || n, 'DATA_QUALITY', 'WARNING', 'NEW', 'E2E', n::text, '홈 시험 예외 ' || n,
              (SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1)
         FROM generate_series(1, ${count}) n`)
}

test('FUN-080 home: 미처리 숫자를 누르면 같은 건수의 예외함이 열린다', async ({ page }) => {
  insertOpenExceptions(3)
  const expected = Number(sql("SELECT count(*) FROM fgc.exception_case WHERE status IN ('NEW','IN_REVIEW')"))
  expect(expected).toBeGreaterThanOrEqual(3)

  await login(page, 'settle01', '/?month=2026-07')
  const kpi = page.getByRole('region', { name: '주요 검증 지표' }).getByRole('link', { name: /^미처리 예외 / })
  await expect(kpi).toHaveAccessibleName(`미처리 예외 ${expected.toLocaleString('ko-KR')}건`)
  await kpi.click()

  await expect(page).toHaveURL(/\/exceptions\?.*status=OPEN/)
  await expect(page.locator('.exception-page .list-header-total').first()).toHaveText(`총 ${expected.toLocaleString('ko-KR')}건`)
})

test('FUN-080 home: 현황 숫자는 DB 와 같고 검색한 설계사의 계약 목록이 열린다', async ({ page }) => {
  const registered = Number(sql(`SELECT count(*) FROM fgc.agent
    WHERE appointment_date <= CURRENT_DATE AND (termination_date IS NULL OR termination_date >= CURRENT_DATE)`))
  const contracts = Number(sql(`SELECT count(*) FROM fgc.insurance_contract c JOIN fgc.agent a ON a.agent_id = c.agent_id
    WHERE a.agent_code = 'A-FC-001' AND c.contract_date <= CURRENT_DATE`))
  expect(contracts).toBeGreaterThan(0)

  await login(page, 'settle01', '/?month=2026-07')
  const region = page.getByRole('region', { name: 'GA·설계사 현황' })
  await expect(region.getByRole('region', { name: '등록 설계사' })).toContainText(String(registered))
  await expect(page.getByText('기준 정산월 2026년 7월')).toBeVisible()
  await expect(region.getByText(/^갱신 \d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)).toBeVisible()

  const search = region.getByRole('region', { name: '설계사 찾기' })
  await search.getByLabel('이름 또는 코드').fill('A-FC-001')
  await search.getByRole('button', { name: '조회' }).click()
  await search.getByRole('link', { name: /A-FC-001/ }).click()

  await expect(page).toHaveURL(/\/contracts\?agentId=\d+&contractDateTo=\d{4}-\d{2}-\d{2}&month=2026-07/)
  await expect(page.locator('.contract-results-card .list-header-total')).toHaveText(`총 ${contracts}건`)
  await expect(page.getByRole('table', { name: '보험계약 검색 결과' }).getByRole('row').nth(1)).toContainText('김정산')
})

test('FUN-080 home: 해촉 설계사도 검색으로 찾는다', async ({ page }) => {
  await login(page, 'settle01', '/?month=2026-07')
  const search = page.getByRole('region', { name: '설계사 찾기' })
  await search.getByLabel('이름 또는 코드').fill('최해촉')
  await search.getByRole('button', { name: '조회' }).click()
  const row = search.getByRole('table', { name: '설계사 검색 결과' }).getByRole('row').nth(1)
  await expect(row).toContainText('A-FC-004')
  await expect(row).toContainText('해촉')
})
