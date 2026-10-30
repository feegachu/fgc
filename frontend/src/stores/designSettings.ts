import type { CSSProperties } from 'react'
import { create } from 'zustand'

export const DESIGN_SETTINGS_KEY = 'fgc.design-settings.v1'
export type SidebarDesign = 'current' | 'a' | 'b' | 'c' | 'glass'
export const extraDesignOptions = {
  buttonColorMode: [
    ['theme', '전체 시안에 자동 맞춤 (기본)'],
    ['custom', '색상 직접 지정'],
  ],
  summaryDisplay: [
    ['donut', '도넛과 범례 (기존)'],
    ['bars', '가로 요약 막대'],
    ['list', '숫자 목록'],
    ['hidden', '표시 안 함'],
  ],
  tabStyle: [
    ['current', '기존 탭'],
    ['underline', '밑줄 탭 (PDF)'],
    ['pills', '라운드 탭'],
    ['compact', '컴팩트 탭'],
    ['glass', '글래스 탭'],
  ],
  tableHeaderStyle: [
    ['current', '기존 머리글'],
    ['subtle', '옅은 배경 (이미지 4)'],
    ['plain', '흰색과 구분선 (이미지 5)'],
    ['accent', '강조색 배경'],
    ['glass', '글래스 머리글'],
  ],
  pageHeaderStyle: [
    ['current', '기존 제목'],
    ['plain', '배경 없는 제목'],
    ['band', '옅은 배경 띠'],
    ['accent', '강조선과 배경'],
    ['glass', '글래스 제목'],
  ],
  backgroundStyle: [
    ['current', '기존 배경'],
    ['white', '화이트'],
    ['soft', '소프트 그레이'],
    ['gradient', '그라데이션'],
    ['mesh', '컬러 메쉬'],
    ['ice', '아이스 블루'],
    ['lavender', '라벤더'],
    ['aurora', '컬러 웨이브'],
  ],
  badgeStyle: [
    ['inherit', '기존 배지 (기본)'],
    ['solid', '채워진 배지'],
    ['soft', '토스형 반투명'],
    ['outline', '테두리 배지'],
    ['dot', '점과 텍스트'],
    ['glass', '글래스 배지'],
  ],
  badgePalette: [
    ['semantic', '상태별 기본색'],
    ['blue', '블루'],
    ['violet', '바이올렛'],
    ['teal', '틸'],
    ['rose', '로즈'],
    ['slate', '슬레이트'],
  ],
} as const
type ExtraPreferences = { [K in keyof typeof extraDesignOptions]: (typeof extraDesignOptions)[K][number][0] }
export const componentKinds = [
  { key: 'buttonDesign', label: '버튼' },
  { key: 'fieldDesign', label: '입력 / 선택' },
  { key: 'tableDesign', label: '테이블' },
] as const
export type ComponentKind = (typeof componentKinds)[number]['key']
type ComponentDesign = SidebarDesign | 'inherit'
export interface DesignPreferences extends ExtraPreferences {
  kpiStyle: 'cards' | 'summary'
  sidebarDesign: SidebarDesign
  sidebarColor: string | null
  componentDesign: SidebarDesign
  buttonDesign: ComponentDesign
  fieldDesign: ComponentDesign
  tableDesign: ComponentDesign
  buttonColor: string | null
  sidebarButtonColor: string | null
  tableHeaderColor: string | null
  tableHeaderLineOpacity: number
  pageHeaderColor: string | null
  backgroundColor: string | null
  badgeColor: string | null
  badgeOpacity: number
  glassSidebar: boolean
  glassButtons: boolean
  glassCards: boolean
  glassTabs: boolean
  glassOpacity: number
  glassBlur: number
}
export const defaultDesignPreferences: DesignPreferences = {
  kpiStyle: 'cards',
  sidebarDesign: 'current',
  sidebarColor: null,
  componentDesign: 'current',
  buttonColorMode: 'theme',
  buttonDesign: 'inherit',
  fieldDesign: 'inherit',
  tableDesign: 'inherit',
  summaryDisplay: 'donut',
  tabStyle: 'current',
  tableHeaderStyle: 'current',
  pageHeaderStyle: 'current',
  backgroundStyle: 'current',
  badgeStyle: 'inherit',
  badgePalette: 'semantic',
  buttonColor: null,
  sidebarButtonColor: null,
  tableHeaderColor: null,
  tableHeaderLineOpacity: 1,
  pageHeaderColor: null,
  backgroundColor: null,
  badgeColor: null,
  badgeOpacity: 0.12,
  glassSidebar: false,
  glassButtons: false,
  glassCards: false,
  glassTabs: false,
  glassOpacity: 0.7,
  glassBlur: 16,
}
export const sidebarDesigns = [
  { id: 'current', name: '기존', description: '현재 디자인', color: '#467aa2' },
  { id: 'a', name: 'A안', description: '화이트 / 블루', color: '#ffffff' },
  { id: 'b', name: 'B안', description: '부드러운 라운드', color: '#ffffff' },
  { id: 'c', name: 'C안', description: '모노 / 컴팩트', color: '#fafafa' },
  { id: 'glass', name: '글래스', description: '반투명 / 블러', color: '#eef4ff' },
] as const

export function readDesignPreferences(): DesignPreferences {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(DESIGN_SETTINGS_KEY) ?? 'null')
    if (!saved || typeof saved !== 'object') return { ...defaultDesignPreferences }
    const value = saved as Record<string, unknown>
    const preferences = { ...defaultDesignPreferences }
    for (const key of Object.keys(preferences) as (keyof DesignPreferences)[]) {
      const candidate = value[key]
      const initial = preferences[key]
      let accepted = false
      if (initial === null)
        accepted = candidate === null || (typeof candidate === 'string' && /^#[\da-f]{6}$/i.test(candidate))
      else if (typeof initial === 'boolean') accepted = typeof candidate === 'boolean'
      else if (typeof initial === 'number') {
        const bounds =
          key === 'glassBlur'
            ? [0, 32]
            : key === 'badgeOpacity'
              ? [0.06, 0.4]
              : key === 'tableHeaderLineOpacity'
                ? [0, 1]
                : [0.35, 0.95]
        accepted =
          typeof candidate === 'number' &&
          Number.isFinite(candidate) &&
          candidate >= bounds[0] &&
          candidate <= bounds[1]
      } else if (key in extraDesignOptions) {
        accepted = extraDesignOptions[key as keyof ExtraPreferences].some(([option]) => option === candidate)
      } else if (key === 'kpiStyle') accepted = candidate === 'cards' || candidate === 'summary'
      else
        accepted =
          sidebarDesigns.some((design) => design.id === candidate) || (initial === 'inherit' && candidate === 'inherit')
      if (accepted) Object.assign(preferences, { [key]: candidate })
    }
    return preferences
  } catch {
    return { ...defaultDesignPreferences }
  }
}

export const useDesignSettings = create<{
  preferences: DesignPreferences
  update: (settings: Partial<DesignPreferences>) => void
  reset: () => void
}>((set, get) => {
  const save = (preferences: DesignPreferences) => {
    try {
      localStorage.setItem(DESIGN_SETTINGS_KEY, JSON.stringify(preferences))
    } catch {
      // UI preferences still work when browser storage is unavailable.
    }
    set({ preferences })
  }
  return {
    preferences: readDesignPreferences(),
    update: (settings) => save({ ...get().preferences, ...settings }),
    reset: () => save({ ...defaultDesignPreferences }),
  }
})

export function resolveComponentDesign(preferences: DesignPreferences, kind: ComponentKind): SidebarDesign {
  return preferences[kind] === 'inherit' ? preferences.componentDesign : preferences[kind]
}

// Compare WCAG contrast with both light and dark text, including mid-tone backgrounds.
export function sidebarUsesDarkText(color: string): boolean {
  const channels = [1, 3, 5].map((offset) => {
    const channel = parseInt(color.slice(offset, offset + 2), 16) / 255
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
  })
  const luminance = channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
  return (luminance + 0.05) / 0.05 > 1.05 / (luminance + 0.05)
}

export function sidebarAppearance(preferences: DesignPreferences, isAgent: boolean) {
  const color =
    preferences.sidebarColor ??
    (preferences.sidebarDesign === 'current'
      ? isAgent
        ? '#7f57ac'
        : '#467aa2'
      : sidebarDesigns.find((design) => design.id === preferences.sidebarDesign)!.color)
  const darkText = sidebarUsesDarkText(color)
  const custom = preferences.sidebarColor !== null
  const redesigned = preferences.sidebarDesign !== 'current'
  const foreground = darkText ? (custom ? '#000000' : '#171717') : '#ffffff'
  const style =
    custom || redesigned
      ? ({
          '--color-nav-bg-default': color,
          '--color-nav-bg-hover': `color-mix(in srgb, ${color}, ${foreground} 7%)`,
          '--color-nav-bg-active': `color-mix(in srgb, ${color}, ${foreground} 12%)`,
          '--color-nav-text-default': foreground,
          '--color-nav-text-muted': foreground,
        } as CSSProperties)
      : undefined
  return { color, darkText: (custom || redesigned) && darkText, style }
}

export function designAppearance(preferences: DesignPreferences, isAgent: boolean): CSSProperties {
  const buttonDesign = resolveComponentDesign(preferences, 'buttonDesign')
  const themeAccent = {
    current: '#1f54e5',
    a: '#0046ff',
    b: '#2070e8',
    c: '#171717',
    glass:
      preferences.backgroundStyle === 'lavender'
        ? '#6d4dc0'
        : preferences.backgroundStyle === 'aurora'
          ? '#465ab8'
          : '#145d95',
  }[buttonDesign]
  const buttonColor = preferences.buttonColorMode === 'custom' ? preferences.buttonColor : null
  const sidebarButtonColor = preferences.buttonColorMode === 'custom' ? preferences.sidebarButtonColor : null
  const palettes = {
    semantic: '#2563eb',
    blue: '#2563eb',
    violet: '#7c3aed',
    teal: '#0f766e',
    rose: '#be185d',
    slate: '#475569',
  }
  return {
    ...sidebarAppearance(preferences, isAgent).style,
    '--design-accent': buttonColor ?? themeAccent,
    '--design-button-custom': buttonColor ?? undefined,
    '--design-button-text': buttonColor && sidebarUsesDarkText(buttonColor) ? '#000000' : '#ffffff',
    '--design-sidebar-active': sidebarButtonColor ?? undefined,
    '--design-sidebar-active-text':
      sidebarButtonColor && sidebarUsesDarkText(sidebarButtonColor) ? '#000000' : '#ffffff',
    '--design-header-color': preferences.tableHeaderColor ?? '#eef2ff',
    '--design-header-text': sidebarUsesDarkText(preferences.tableHeaderColor ?? '#eef2ff') ? '#171717' : '#ffffff',
    '--design-header-line-opacity': `${preferences.tableHeaderLineOpacity * 100}%`,
    '--design-title-color': preferences.pageHeaderColor ?? '#f1f5f9',
    '--design-title-text': sidebarUsesDarkText(preferences.pageHeaderColor ?? '#f1f5f9') ? '#171717' : '#ffffff',
    '--design-canvas': preferences.backgroundColor ?? undefined,
    '--design-badge-accent': preferences.badgeColor ?? palettes[preferences.badgePalette],
    '--design-badge-opacity': `${preferences.badgeOpacity * 100}%`,
    '--design-glass-opacity': `${preferences.glassOpacity * 100}%`,
    '--design-glass-blur': `${preferences.glassBlur}px`,
  } as CSSProperties
}
