import { beforeEach, expect, it, vi } from 'vitest'
import {
  defaultDesignPreferences,
  DESIGN_SETTINGS_KEY,
  readDesignPreferences,
  sidebarAppearance,
  useDesignSettings,
} from './designSettings'

beforeEach(() => {
  localStorage.clear()
  useDesignSettings.setState({ preferences: { ...defaultDesignPreferences } })
})

it('설정을 다시 읽어 복원하고 알 수 없는 값이나 손상된 저장값은 기본값으로 처리한다', () => {
  useDesignSettings.getState().update({ kpiStyle: 'summary', sidebarDesign: 'c', sidebarColor: '#123456' })
  expect(readDesignPreferences()).toEqual({
    ...defaultDesignPreferences,
    kpiStyle: 'summary',
    sidebarDesign: 'c',
    sidebarColor: '#123456',
  })
  localStorage.setItem(
    DESIGN_SETTINGS_KEY,
    JSON.stringify({ kpiStyle: 'unknown', sidebarDesign: 'unknown', sidebarColor: 'red' }),
  )
  expect(readDesignPreferences()).toEqual(defaultDesignPreferences)
  localStorage.setItem(DESIGN_SETTINGS_KEY, '{broken')
  expect(readDesignPreferences()).toEqual(defaultDesignPreferences)
})

it('저장이 차단돼도 설정을 적용하고 기본값으로 복원할 수 있다', () => {
  const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('storage blocked')
  })
  try {
    useDesignSettings.getState().update({ kpiStyle: 'summary', sidebarDesign: 'a' })
    expect(useDesignSettings.getState().preferences.sidebarDesign).toBe('a')
    useDesignSettings.getState().reset()
    expect(useDesignSettings.getState().preferences).toEqual(defaultDesignPreferences)
  } finally {
    storage.mockRestore()
  }
})

it('기존 역할별 색은 유지하고 사용자 배경색에 대비되는 글자색을 선택한다', () => {
  expect(sidebarAppearance(defaultDesignPreferences, true)).toMatchObject({
    color: '#7f57ac',
    style: undefined,
    darkText: false,
  })
  expect(sidebarAppearance({ ...defaultDesignPreferences, sidebarColor: '#ffffff' }, false)).toMatchObject({
    darkText: true,
    style: { '--color-nav-text-default': '#000000' },
  })
  expect(sidebarAppearance({ ...defaultDesignPreferences, sidebarColor: '#101010' }, false)).toMatchObject({
    darkText: false,
    style: { '--color-nav-text-default': '#ffffff' },
  })
})
