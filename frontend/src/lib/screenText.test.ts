import { expect, it } from 'vitest'
import { errorScreenMessage, isDeveloperText, monthText, nameWithCode, screenMessage } from './screenText'

it('기존 서버 안내 문구를 한국어 업무 표현으로 표시한다', () => {
  expect(screenMessage('실제 지급 없음 — 예상 5,000원 지급 건 미발견')).toBe('실제 지급 누락 (예상 5,000원)')
  expect(screenMessage('정책·규칙 관리')).toBe('정책 및 규칙 관리')
  expect(screenMessage('지급단계 INSURER_TO_GA · 예상 5,000원')).toBe('지급단계 보험회사→GA 예상 5,000원')
  expect(screenMessage('—')).toBe('-')
  expect(screenMessage(null)).toBe('-')
  expect(screenMessage('계약 (FGC-CONT-001)')).toBe('계약 (FGC-CONT-001)')
  expect(screenMessage('제출 불가 — 근거를 입력하세요.')).toBe('제출할 수 없습니다. 근거를 입력하세요.')
  expect(screenMessage('초년도 밖 — 판정 대상 아님')).toBe('초년도 밖, 판정 대상 아님')
  expect(errorScreenMessage('처리 실패 (FGC-CONT-001, 요청 ID: req-1)')).toBe('처리 실패 (요청번호: req-1)')
  expect(errorScreenMessage('처리 실패 (FGC-CONT-001)')).toBe('처리 실패')
  expect(errorScreenMessage('처리 실패 (FGC-CONT-001, 요청 ID: req-1)', 'req-1')).toBe('처리 실패 (요청번호: req-1)')
  expect(errorScreenMessage('처리 실패 (요청번호: req-1)', 'req-1')).toBe('처리 실패 (요청번호: req-1)')
})

it('개발자 문장을 가려내고 요청번호는 보지 않는다', () => {
  expect(isDeveloperText('journal_no 월간 일련번호 상한(99999) 초과')).toBe(true)
  expect(isDeveloperText('FGC-VRUN-003')).toBe(true)
  expect(isDeveloperText('계약 FGC-FGL01-202607-0001 자료를 확인하세요.')).toBe(false)
  expect(isDeveloperText('FGC-VRUN-003 계약 FGC-FGL01-202607-0001')).toBe(true)
  expect(isDeveloperText('DB 오류 (23505): id=42')).toBe(true)
  expect(isDeveloperText('DB 오류 (23505)')).toBe(true)
  expect(isDeveloperText('지급 건 ID가 없습니다.')).toBe(false)
  expect(isDeveloperText('처리 중 오류가 발생했습니다. 요청번호 abc.def_ghi-123를 담당자에게 알려주세요.')).toBe(false)
  expect(isDeveloperText('GA→설계사 1,200% 한도 초과')).toBe(false)
})

it('월과 회차에 쓸 월 표시를 만들고 업무 식별자는 이름 뒤 괄호에 보존한다', () => {
  expect(monthText('2026-07-01')).toBe('2026년 7월')
  expect(monthText('2026-13')).toBe('-')
  expect(nameWithCode('김정산', 'A-FC-001')).toBe('김정산 (A-FC-001)')
  expect(nameWithCode(null, null)).toBe('-')
})
