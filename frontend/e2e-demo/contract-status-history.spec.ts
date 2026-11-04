import { test, expect, login, responseFor, successful, sql } from './fixtures'

// 화면이 실제 서버와 DB의 IF-API-19, 16, 89 응답을 읽는지 본다. 이 서버는 후속 worker를 꺼 두므로 처리 결과만 SQL로 남기고,
// worker 동작 자체는 ContractStatusHistoryIntegrationTest가 확인한다.
test('CONT-W03 상태 변경 뒤 CONT-W02 이력과 후속 상태, 영향을 실제 API로 보이고 AGENT는 본인 계약만 본다', async ({
  page,
}, testInfo) => {
  test.setTimeout(240_000)
  const contract = sql(`SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL01-202607-0001'`)
  await login(page, 'settle01', `/contracts/${contract}/edit`)
  await page.getByLabel('변경 상태').selectOption('UNPAID')
  await page.getByLabel('효력일시').fill('2026-08-01T00:00')
  await page.getByLabel('사유코드').selectOption('MANUAL_STATUS_CHANGE')
  await page.getByLabel(/^사유\s*\*?$/).fill('보험회사 미납 통보 반영')
  await page.getByRole('button', { name: '수정 저장' }).click()
  const saved = responseFor(page, `/contracts/${contract}`, 'PUT')
  await page.getByRole('dialog', { name: '상태 변경 저장 확인' }).getByRole('button', { name: '확인하고 저장' }).click()
  const response = await successful(saved)
  expect(response.followUp).toEqual({ status: 'PENDING', eventId: response.statusEventId })
  await expect(page.getByRole('heading', { name: '보험계약 상세' })).toBeVisible()

  const table = page.getByRole('table', { name: '상태 사건' })
  const first = table.getByRole('row').filter({ has: page.getByRole('button', { name: '사건 1 영향 보기' }) })
  const second = table.getByRole('row').filter({ has: page.getByRole('button', { name: '사건 2 영향 보기' }) })
  // 시드 사건은 후속 작업이 없고 처리자가 원천에 없다. 수기 사건은 대기, 실제 처리자, 자유 사유를 보인다.
  await expect(first).toContainText('대상 없음')
  await expect(first).toContainText('자료 없음')
  await expect(first).toContainText('신규 계약')
  await expect(second).toContainText('정상 → 미납')
  await expect(second).toContainText('처리 대기')
  await expect(second).toContainText('(settle01)')
  await expect(second).toContainText('보험회사 미납 통보 반영')
  await page.screenshot({ path: testInfo.outputPath('real-status-history-pending.png'), fullPage: true })

  await second.getByRole('button', { name: '사건 2 영향 보기' }).click()
  const panel = page.getByRole('dialog', { name: '사건 2 영향' })
  await expect(panel.getByRole('heading', { name: '영향 0건' })).toBeVisible()
  await expect(panel.getByText('후속 처리 대기 중입니다. 처리가 끝나면 영향이 표시됩니다.')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('real-status-effects-pending.png') })

  // worker가 재시도 끝에 끝낸 결과. 패널을 연 채로 자동 재조회가 읽어 와야 한다.
  const event = Number(response.statusEventId)
  sql(`UPDATE fgc.contract_event_job SET state = 'SUCCEEDED', attempt_count = 2, last_attempt_at = clock_timestamp()
        WHERE contract_status_event_id = ${event};
       INSERT INTO fgc.contract_status_event_processing(contract_status_event_id, processing_job, processing_status, failure_reason)
       VALUES (${event}, 'PaymentHoldJob', 'FAILED', 'IllegalStateException: 내부 메시지'),
              (${event}, 'PaymentHoldJob', 'SUCCEEDED', NULL);
       INSERT INTO fgc.contract_status_event_effect(contract_status_event_id, processing_job, entity_type, entity_id,
              previous_state, next_state, amount, policy_version_id, reason_code, reason)
       VALUES (${event}, 'PaymentHoldJob', 'PAYMENT', 9001, 'DRAFT', 'HELD', 5000, NULL, 'STATUS_UNPAID', '귀속일 시점 미납이라 보류합니다.'),
              (${event}, 'PaymentHoldJob', 'PAYMENT', 9002, 'CONFIRMED', 'EXCLUDED', 0, NULL, 'ALREADY_CONFIRMED', '이미 확정된 지급 건이라 보류하지 않습니다(이력 보존).'),
              (${event}, 'PaymentHoldJob', 'SCHEDULE_LINE', 9003, 'PLANNED', 'HOLD', NULL, NULL, 'STATUS_UNPAID', '미납 회차를 보류합니다.'),
              (${event}, 'PaymentHoldJob', 'SCHEDULE_LINE', 9004, 'PLANNED', 'CANCELLED', 1000, NULL, 'STATUS_INACTIVE', '지급 예정을 중단합니다.');`)
  await expect(panel.getByRole('heading', { name: '영향 4건' })).toBeVisible({ timeout: 20_000 })
  await expect(panel).toContainText('재시도 후 성공, 2/5회 시도')
  await expect(panel).toContainText('실패 사유: 시스템 오류')
  await expect(panel).not.toContainText('IllegalStateException')
  await expect(panel).not.toContainText('내부 메시지')
  await expect(panel.getByRole('row', { name: /지급 건 #9001/ })).toContainText('지급 보류')
  await expect(panel.getByRole('row', { name: /지급 건 #9001/ })).toContainText('5,000원')
  await expect(panel.getByRole('row', { name: /지급 건 #9002/ })).toContainText('이력 보존, 처리 제외')
  await expect(panel.getByRole('row', { name: /지급 건 #9002/ })).toContainText('0원')
  await expect(panel.getByRole('row', { name: /스케줄 회차 #9003/ })).toContainText('회차 보류')
  await expect(panel.getByRole('row', { name: /스케줄 회차 #9003/ })).toContainText('자료 없음')
  await expect(panel.getByRole('row', { name: /스케줄 회차 #9004/ })).toContainText('지급 예정 중단')
  await page.screenshot({ path: testInfo.outputPath('real-status-effects-done.png') })
  await panel.getByRole('button', { name: '닫기', exact: true }).click()
  await expect(second).toHaveClass(/is-selected/)
  await expect(second).toContainText('지급 보류 1건, 회차 변경 2건, 처리 제외 1건')
  await page.locator('.contract-history-panel').screenshot({ path: testInfo.outputPath('real-status-history-done.png') })

  // 시드에서 이 계약은 agent01의 본인 계약이다. V64가 꺼 둔 계정이라 시험 뒤 되돌아가는 DB에서만 켠다.
  sql(
    "UPDATE fgc.app_user SET account_status='ACTIVE', password_hash=(SELECT password_hash FROM fgc.app_user WHERE login_id='settle01') WHERE login_id='agent01'",
  )
  const other = sql(
    `SELECT min(c.contract_id) FROM fgc.insurance_contract c JOIN fgc.agent a USING (agent_id) WHERE a.agent_code <> 'A-FC-001'`,
  )
  await login(page, 'agent01', `/contracts/${contract}`)
  await page.getByRole('button', { name: '사건 2 영향 보기' }).click()
  const own = page.getByRole('dialog', { name: '사건 2 영향' })
  await expect(own.getByRole('row', { name: /지급 건 #9001/ })).toContainText('지급 보류')
  await page.screenshot({ path: testInfo.outputPath('real-status-effects-agent.png') })
  await own.getByRole('button', { name: '닫기', exact: true }).click()
  await page.goto(`contracts/${other}`)
  await expect(page.getByRole('alert')).toContainText('본인 담당 자료만')
  await expect(page.getByRole('table', { name: '상태 사건' })).toHaveCount(0)
})
