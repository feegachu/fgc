import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it } from 'vitest'
import { defaultDesignPreferences, readDesignPreferences, useDesignSettings } from '../../stores/designSettings'
import { DeveloperSettings } from './DeveloperSettings'

beforeEach(() => {
  localStorage.clear()
  useDesignSettings.setState({ preferences: { ...defaultDesignPreferences } })
})

it('설정을 펼쳐 KPI와 시안을 변경하고 기본값 복원 후 Escape로 닫는다', async () => {
  const user = userEvent.setup()
  render(<DeveloperSettings sidebarColor="#467aa2" />)
  const trigger = screen.getByRole('button', { name: '개발자' })
  expect(trigger).toHaveAttribute('aria-expanded', 'false')
  await user.click(trigger)
  expect(screen.getByRole('radio', { name: /1. 기존 카드형/ })).toBeChecked()
  const summary = screen.getByRole('radio', { name: /2. 한 줄 요약형/ })
  await user.click(summary)
  expect(summary).toBeChecked()
  await user.click(screen.getByRole('radio', { name: /A안/ }))
  expect(readDesignPreferences()).toMatchObject({ kpiStyle: 'summary', sidebarDesign: 'a', sidebarColor: null })
  fireEvent.input(screen.getByLabelText('사이드바 배경색'), { target: { value: '#102030' } })
  expect(readDesignPreferences().sidebarColor).toBe('#102030')
  await user.click(screen.getByRole('button', { name: '기본값 복원' }))
  expect(screen.getByRole('radio', { name: /1. 기존 카드형/ })).toBeChecked()
  expect(
    within(screen.getByRole('group', { name: '사이드바 디자인' })).getByRole('radio', { name: /기존/ }),
  ).toBeChecked()
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('region', { name: '개발자 설정' })).not.toBeInTheDocument()
  expect(trigger).toHaveFocus()
})

it('컴포넌트 전체 스타일과 개별 디자인을 조합하고 일괄 선택 시 개별 설정을 초기화한다', async () => {
  const user = userEvent.setup()
  render(<DeveloperSettings sidebarColor="#467aa2" />)
  await user.click(screen.getByRole('button', { name: '개발자' }))
  await user.selectOptions(screen.getByRole('combobox', { name: '전체 스타일' }), 'b')
  await user.selectOptions(screen.getByRole('combobox', { name: '버튼' }), 'c')
  expect(readDesignPreferences()).toMatchObject({ componentDesign: 'b', buttonDesign: 'c', fieldDesign: 'inherit' })
  await user.selectOptions(screen.getByRole('combobox', { name: '전체 스타일' }), 'a')
  expect(readDesignPreferences()).toMatchObject({ componentDesign: 'a', buttonDesign: 'inherit' })
})

it('시안을 선택하면 사용자 지정 색을 해제하고 바깥 클릭으로 접는다', async () => {
  const user = userEvent.setup()
  useDesignSettings.getState().update({ sidebarColor: '#111111' })
  render(
    <>
      <DeveloperSettings sidebarColor="#111111" />
      <button type="button">본문 버튼</button>
    </>,
  )
  await user.click(screen.getByRole('button', { name: '개발자' }))
  await user.click(screen.getByRole('radio', { name: /B안/ }))
  expect(useDesignSettings.getState().preferences.sidebarColor).toBeNull()
  await user.click(screen.getByRole('button', { name: '본문 버튼' }))
  expect(screen.getByRole('button', { name: '개발자' })).toHaveAttribute('aria-expanded', 'false')
})

it('독립적인 표시·색상 설정과 글래스 프리셋을 저장하고 모두 기본값으로 복원한다', async () => {
  const user = userEvent.setup()
  render(<DeveloperSettings sidebarColor="#467aa2" />)
  await user.click(screen.getByRole('button', { name: '개발자' }))
  await user.selectOptions(screen.getByRole('combobox', { name: '검증 현황 요약 표시' }), 'bars')
  await user.selectOptions(screen.getByRole('combobox', { name: '상단 탭바' }), 'underline')
  await user.selectOptions(screen.getByRole('combobox', { name: '표 컬럼 머리글' }), 'plain')
  fireEvent.input(screen.getByLabelText('공통 버튼색'), { target: { value: '#112233' } })
  fireEvent.input(screen.getByLabelText('사이드바 메뉴 버튼색'), { target: { value: '#aabbcc' } })
  expect(readDesignPreferences()).toMatchObject({
    summaryDisplay: 'bars',
    tabStyle: 'underline',
    tableHeaderStyle: 'plain',
    buttonColor: '#112233',
    sidebarButtonColor: '#aabbcc',
    sidebarColor: null,
  })
  await user.click(screen.getByRole('button', { name: '라벤더' }))
  expect(readDesignPreferences()).toMatchObject({
    backgroundStyle: 'lavender',
    glassSidebar: true,
    glassCards: true,
    glassButtons: true,
    glassTabs: true,
    badgeStyle: 'glass',
    glassBlur: 22,
  })
  await user.click(screen.getByRole('checkbox', { name: '사이드바 유리 효과' }))
  expect(readDesignPreferences().glassSidebar).toBe(false)
  await user.click(screen.getByRole('button', { name: '기본값 복원' }))
  expect(readDesignPreferences()).toEqual(defaultDesignPreferences)
})
