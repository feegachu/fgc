import type { CSSProperties } from 'react'
import { create } from 'zustand'

export const DESIGN_SETTINGS_KEY = 'fgc.design-settings.v1'
export type SidebarDesign = 'current' | 'a' | 'b' | 'c'
export const componentKinds = [
  { key: 'buttonDesign', label: '버튼' },
  { key: 'fieldDesign', label: '입력 / 선택' },
  { key: 'tableDesign', label: '테이블' },
  { key: 'badgeDesign', label: '상태 표시' },
] as const
export type ComponentKind = (typeof componentKinds)[number]['key']
type ComponentDesign = SidebarDesign | 'inherit'
export interface DesignPreferences {
  kpiStyle: 'cards' | 'summary'
  sidebarDesign: SidebarDesign
  sidebarColor: string | null
  componentDesign: SidebarDesign
  buttonDesign: ComponentDesign
  fieldDesign: ComponentDesign
  tableDesign: ComponentDesign
  badgeDesign: ComponentDesign
}
export const defaultDesignPreferences: DesignPreferences = {
  kpiStyle: 'cards',
  sidebarDesign: 'current',
  sidebarColor: null,
  componentDesign: 'current',
  buttonDesign: 'inherit',
  fieldDesign: 'inherit',
  tableDesign: 'inherit',
  badgeDesign: 'inherit',
}
export const sidebarDesigns = [
  { id: 'current', name: '기존', description: '현재 디자인', color: '#467aa2' },
  { id: 'a', name: 'A안', description: '화이트 / 블루', color: '#ffffff' },
  { id: 'b', name: 'B안', description: '부드러운 라운드', color: '#ffffff' },
  { id: 'c', name: 'C안', description: '모노 / 컴팩트', color: '#fafafa' },
] as const

export function readDesignPreferences(): DesignPreferences {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(DESIGN_SETTINGS_KEY) ?? 'null')
    if (!saved || typeof saved !== 'object') return { ...defaultDesignPreferences }
    const value = saved as Record<string, unknown>
    const design = (key: string, fallback: ComponentDesign): ComponentDesign =>
      sidebarDesigns.some((option) => option.id === value[key]) ? (value[key] as SidebarDesign) : fallback
    return {
      kpiStyle: value.kpiStyle === 'summary' ? 'summary' : 'cards',
      sidebarDesign: sidebarDesigns.some((design) => design.id === value.sidebarDesign)
        ? (value.sidebarDesign as SidebarDesign)
        : 'current',
      sidebarColor:
        typeof value.sidebarColor === 'string' && /^#[\da-f]{6}$/i.test(value.sidebarColor) ? value.sidebarColor : null,
      componentDesign: design('componentDesign', 'current') as SidebarDesign,
      buttonDesign: design('buttonDesign', 'inherit'),
      fieldDesign: design('fieldDesign', 'inherit'),
      tableDesign: design('tableDesign', 'inherit'),
      badgeDesign: design('badgeDesign', 'inherit'),
    }
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
