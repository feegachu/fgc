import { expect, it } from 'vitest'
import { errorScreenMessage, monthText, nameWithCode, screenMessage } from './screenText'

it('기존 서버 안내 문구를 한국어 업무 표현으로 표시한다', () => {
  expect(screenMessage('실제 지급 없음 — 예상 5,000원 지급 건 미발견')).toBe('실제 지급 누락 (예상 5,000원)')
  expect(screenMessage('정책·룰셋 관리')).toBe('정책/룰셋 관리')
  expect(screenMessage('지급단계 INSURER_TO_GA · 예상 5,000원')).toBe('지급단계 원수사 → GA, 예상 5,000원')
  expect(screenMessage('—')).toBe('-')
  expect(screenMessage(null)).toBe('-')
  expect(screenMessage('계약 (FGC-CONT-001)')).toBe('계약 (FGC-CONT-001)')
  expect(errorScreenMessage('처리 실패 (FGC-CONT-001, 요청 ID: req-1)')).toBe('처리 실패')
})

it('월과 회차에 쓸 월 표시를 만들고 업무 식별자는 이름 뒤 괄호에 보존한다', () => {
  expect(monthText('2026-07-01')).toBe('2026년 7월')
  expect(monthText('2026-13')).toBe('-')
  expect(nameWithCode('김정산', 'A-FC-001')).toBe('김정산 (A-FC-001)')
  expect(nameWithCode(null, null)).toBe('-')
})
