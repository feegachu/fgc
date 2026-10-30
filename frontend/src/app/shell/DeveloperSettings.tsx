import { useEffect, useId, useRef, useState } from 'react'
import { componentKinds, extraDesignOptions, sidebarDesigns, useDesignSettings } from '../../stores/designSettings'
import type { DesignPreferences, SidebarDesign } from '../../stores/designSettings'
import './developer-settings.css'

const buttonColorPresets = [
  { id: 'a', name: 'A안 · 블루 라인' },
  { id: 'b', name: 'B안 · 소프트 블루' },
  { id: 'c', name: 'C안 · 라이트 그레이' },
] as const

export function DeveloperSettings({ sidebarColor }: { sidebarColor: string }) {
  const [open, setOpen] = useState(false)
  const [wide, setWide] = useState(true)
  const root = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const id = useId()
  const preferences = useDesignSettings((state) => state.preferences)
  const update = useDesignSettings((state) => state.update)
  const reset = useDesignSettings((state) => state.reset)

  useEffect(() => {
    if (!open) return
    const dismiss = (event: PointerEvent | KeyboardEvent) => {
      if (event instanceof KeyboardEvent) {
        if (event.key !== 'Escape') return
        setOpen(false)
        trigger.current?.focus()
      } else if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', dismiss)
    document.addEventListener('keydown', dismiss)
    return () => {
      document.removeEventListener('pointerdown', dismiss)
      document.removeEventListener('keydown', dismiss)
    }
  }, [open])

  return (
    <div className="developer-settings" ref={root}>
      <button
        type="button"
        className="developer-settings-trigger"
        ref={trigger}
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen(!open)}
      >
        <span className="developer-settings-code" aria-hidden="true">
          {'</>'}
        </span>
        개발자
        <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
          <path d="m3 4.5 3 3 3-3" fill="none" stroke="currentColor" strokeWidth="1.5" />
        </svg>
      </button>
      {open && (
        <section id={id} className={`developer-settings-panel ${wide ? 'is-wide' : ''}`} aria-label="개발자 설정">
          <header className="design-panel-heading">
            <div>
              <span className="design-panel-eyebrow">DESIGN CONTROLS</span>
              <h2>화면 디자인 설정</h2>
              <p>설정을 바꾸면 현재 화면에 바로 반영됩니다.</p>
            </div>
            <button type="button" className="design-reset" aria-pressed={wide} onClick={() => setWide(!wide)}>
              {wide ? '작게 보기' : '넓게 보기'}
            </button>
            <button type="button" className="design-reset" onClick={reset}>
              기본값 복원
            </button>
          </header>
          <fieldset className="design-kpi-setting">
            <legend>전체 KPI 카드 통일</legend>
            <p>모든 화면의 KPI에 대시보드 카드 기준을 적용합니다. 선택한 디자인도 전 화면에 적용됩니다.</p>
            <div className="design-kpi-versions">
              {(
                [
                  { id: 'cards', name: '1. 기존 카드형', description: '기본 / 개별 카드와 상태 막대' },
                  { id: 'summary', name: '2. 한 줄 요약형', description: '새 디자인 / 하나의 면으로 묶기' },
                ] as const
              ).map((version) => (
                <label className="design-preset" key={version.id}>
                  <input
                    type="radio"
                    name={`${id}-kpi`}
                    checked={preferences.kpiStyle === version.id}
                    onChange={() => update({ kpiStyle: version.id })}
                  />
                  <span className="design-preset-content">
                    <strong>{version.name}</strong>
                    <small>{version.description}</small>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
          <fieldset className="design-sidebar-options">
            <legend>사이드바 디자인</legend>
            <div className="design-preset-grid">
              {sidebarDesigns.map((design) => (
                <label className="design-preset" key={design.id}>
                  <input
                    type="radio"
                    name={`${id}-sidebar`}
                    value={design.id}
                    checked={preferences.sidebarDesign === design.id}
                    onChange={() =>
                      update({ sidebarDesign: design.id, sidebarColor: null, glassSidebar: design.id === 'glass' })
                    }
                  />
                  <span className={`design-preset-content design-preset-${design.id}`}>
                    <span className="design-preset-thumbnail" aria-hidden="true">
                      <i />
                      <i />
                      <i />
                    </span>
                    <strong>{design.name}</strong>
                    <small>{design.description}</small>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
          <div className="design-color-setting">
            <div>
              <h3>사이드바 배경색</h3>
              <p>디자인을 유지하면서 원하는 색으로 변경합니다.</p>
            </div>
            <label className="design-color-picker">
              <input
                type="color"
                aria-label="사이드바 배경색"
                value={sidebarColor}
                onInput={(event) => update({ sidebarColor: event.currentTarget.value })}
                onChange={(event) => update({ sidebarColor: event.target.value })}
              />
              <span>{sidebarColor.toUpperCase()}</span>
            </label>
            <button
              type="button"
              className="design-reset"
              disabled={preferences.sidebarColor === null}
              onClick={() => update({ sidebarColor: null })}
            >
              색상 복원
            </button>
          </div>
          <fieldset className="design-component-options">
            <legend>공통 컴포넌트 디자인</legend>
            <p>PDF의 색상, 둥글기, 크기 기준을 조합해 비교합니다.</p>
            <label className="design-component-bulk">
              <span>전체 스타일</span>
              <select
                value={preferences.componentDesign}
                onChange={(event) =>
                  update({
                    componentDesign: event.target.value as SidebarDesign,
                    buttonColorMode: 'theme',
                    glassButtons: event.target.value === 'glass',
                    glassCards: event.target.value === 'glass',
                    buttonDesign: 'inherit',
                    fieldDesign: 'inherit',
                    tableDesign: 'inherit',
                    badgeDesign: 'inherit',
                  })
                }
              >
                <option value="current">기존 디자인 (기본값)</option>
                <option value="a">A안 / 표준 블루</option>
                <option value="b">B안 / 부드러운 라운드</option>
                <option value="c">C안 / 모노 컴팩트</option>
                <option value="glass">글래스 / 반투명</option>
              </select>
            </label>
            <div className="design-component-grid">
              {componentKinds.map((kind) => (
                <label key={kind.key}>
                  <span>{kind.label}</span>
                  <select
                    value={preferences[kind.key]}
                    onChange={(event) =>
                      update({
                        [kind.key]: event.target.value,
                        ...(kind.key === 'buttonDesign'
                          ? {
                              buttonColorMode: 'theme',
                              glassButtons:
                                event.target.value === 'glass' ||
                                (event.target.value === 'inherit' && preferences.componentDesign === 'glass'),
                            }
                          : {}),
                      } as Partial<DesignPreferences>)
                    }
                  >
                    <option value="inherit">전체 스타일 따름</option>
                    <option value="current">기존 디자인</option>
                    <option value="a">{kind.key === 'buttonDesign' ? 'A안 / 블루 라인' : 'A안 / 표준 블루'}</option>
                    <option value="b">{kind.key === 'buttonDesign' ? 'B안 / 소프트 블루' : 'B안 / 라운드'}</option>
                    <option value="c">{kind.key === 'buttonDesign' ? 'C안 / 라이트 그레이' : 'C안 / 컴팩트'}</option>
                    <option value="glass">글래스 / 반투명</option>
                  </select>
                </label>
              ))}
            </div>
          </fieldset>
          <fieldset className="design-experiment-group">
            <legend>요약, 탭바와 컬럼바</legend>
            <ChoiceSetting label="검증 현황 요약 표시" setting="summaryDisplay" />
            <ChoiceSetting label="상단 탭바" setting="tabStyle" />
            <ChoiceSetting label="표 컬럼 머리글" setting="tableHeaderStyle" />
            <ColorSetting label="컬럼 머리글 색상" setting="tableHeaderColor" fallback="#eef2ff" />
            <RangeSetting
              label="표 구분선 불투명도"
              setting="tableHeaderLineOpacity"
              min={0}
              max={1}
              step={0.01}
              percent
            />
            <p>머리글과 데이터 행 사이의 가로 구분선에 적용됩니다. 0%는 숨김, 100%는 기존 농도입니다.</p>
          </fieldset>
          <fieldset className="design-experiment-group">
            <legend>제목과 화면 배경</legend>
            <ChoiceSetting label="화면 제목 배경" setting="pageHeaderStyle" />
            <ColorSetting label="제목 배경색" setting="pageHeaderColor" fallback="#f1f5f9" />
            <ChoiceSetting label="전체 화면 배경" setting="backgroundStyle" />
            <ColorSetting label="전체 배경색" setting="backgroundColor" fallback="#f8fafc" />
          </fieldset>
          <fieldset className="design-experiment-group">
            <legend>버튼 색상</legend>
            <p>신규 등록 링크와 조회·저장 등 공통 버튼에 적용합니다. A·B·C안은 옅은 배경과 진한 글자로 표시합니다.</p>
            <ChoiceSetting label="버튼 색상 적용" setting="buttonColorMode" />
            {preferences.buttonColorMode === 'custom' ? (
              <>
                <ColorSetting label="사이드바 메뉴 버튼색" setting="sidebarButtonColor" fallback="#e8eeff" />
                <ColorSetting label="공통 버튼색" setting="buttonColor" fallback="#1f54e5" />
              </>
            ) : (
              <>
                <button
                  type="button"
                  className="design-reset design-button-preset-follow"
                  aria-pressed={preferences.buttonDesign === 'inherit'}
                  onClick={() =>
                    update({
                      buttonDesign: 'inherit',
                      buttonColorMode: 'theme',
                      glassButtons: preferences.componentDesign === 'glass',
                    })
                  }
                >
                  전체 스타일 따름
                </button>
                <div className="design-button-presets" role="group" aria-label="공통 버튼 색상 시안">
                  {buttonColorPresets.map((preset) => (
                    <button
                      key={preset.id}
                      type="button"
                      className="design-button-preset"
                      data-button-preset={preset.id}
                      aria-pressed={preferences.buttonDesign === preset.id}
                      onClick={() => update({ buttonDesign: preset.id, buttonColorMode: 'theme', glassButtons: false })}
                    >
                      <span>{preset.name}</span>
                      <span className="design-button-preset-sample">＋ 신규 등록</span>
                    </button>
                  ))}
                </div>
              </>
            )}
          </fieldset>
          <fieldset className="design-experiment-group">
            <legend>계약 상태와 배지</legend>
            <ChoiceSetting label="배지 표현" setting="badgeStyle" />
            <ChoiceSetting label="일반 상태 색상 팔레트" setting="badgePalette" />
            <ColorSetting label="일반 상태 사용자 색상" setting="badgeColor" fallback="#2563eb" />
            <RangeSetting label="배지 배경 농도" setting="badgeOpacity" min={0.06} max={0.4} step={0.01} percent />
          </fieldset>
          <fieldset className="design-experiment-group design-glass-controls">
            <legend>글래스 모피즘</legend>
            <p>컬러 배경, 반투명 패널, 블러와 유리 테두리를 함께 적용합니다.</p>
            <div className="design-glass-presets">
              {(['ice', 'lavender', 'aurora'] as const).map((preset, index) => (
                <button
                  key={preset}
                  type="button"
                  className={`design-glass-preset is-${preset}`}
                  onClick={() =>
                    update({
                      backgroundStyle: preset,
                      backgroundColor: null,
                      sidebarDesign: 'glass',
                      sidebarColor: null,
                      componentDesign: 'glass',
                      buttonColorMode: 'theme',
                      buttonDesign: 'inherit',
                      fieldDesign: 'inherit',
                      tableDesign: 'inherit',
                      badgeDesign: 'inherit',
                      glassSidebar: true,
                      glassButtons: true,
                      glassCards: true,
                      glassTabs: true,
                      tabStyle: 'glass',
                      tableHeaderStyle: 'glass',
                      pageHeaderStyle: 'glass',
                      tableHeaderColor: null,
                      pageHeaderColor: null,
                      badgeStyle: 'glass',
                      badgePalette: preset === 'lavender' ? 'violet' : 'blue',
                      badgeColor: null,
                      sidebarButtonColor: null,
                      buttonColor: null,
                      glassOpacity: preset === 'aurora' ? 0.45 : preset === 'lavender' ? 0.58 : 0.62,
                      glassBlur: 22,
                    })
                  }
                >
                  {['아이스 블루', '라벤더', '컬러 웨이브'][index]}
                </button>
              ))}
            </div>
            <div className="design-glass-toggles">
              {(
                [
                  ['glassSidebar', '사이드바 유리 효과'],
                  ['glassButtons', '버튼 유리 효과'],
                  ['glassCards', '카드와 패널 유리 효과'],
                  ['glassTabs', '탭바 유리 효과'],
                ] as const
              ).map(([setting, label]) => (
                <label key={setting}>
                  <input
                    type="checkbox"
                    checked={preferences[setting]}
                    onChange={(event) => update({ [setting]: event.target.checked })}
                  />
                  {label}
                </label>
              ))}
            </div>
            <RangeSetting label="유리 불투명도" setting="glassOpacity" min={0.35} max={0.95} step={0.01} percent />
            <RangeSetting label="배경 블러" setting="glassBlur" min={0} max={32} step={1} />
          </fieldset>
          <footer className="design-panel-footer">
            <span>이 브라우저에 자동 저장됩니다.</span>
            <button
              type="button"
              onClick={() => {
                setOpen(false)
                trigger.current?.focus()
              }}
            >
              접기 ↑
            </button>
          </footer>
        </section>
      )}
    </div>
  )
}

function ChoiceSetting<K extends keyof typeof extraDesignOptions>({ label, setting }: { label: string; setting: K }) {
  const value = useDesignSettings((state) => state.preferences[setting])
  const update = useDesignSettings((state) => state.update)
  return (
    <label className="design-setting-row">
      <span>{label}</span>
      <select
        value={value}
        onChange={(event) =>
          update({
            [setting]: event.target.value,
            ...(setting === 'tabStyle' ? { glassTabs: event.target.value === 'glass' } : {}),
          } as Partial<DesignPreferences>)
        }
      >
        {extraDesignOptions[setting].map(([key, name]) => (
          <option key={key} value={key}>
            {name}
          </option>
        ))}
      </select>
    </label>
  )
}
type ColorPreference =
  'buttonColor' | 'sidebarButtonColor' | 'tableHeaderColor' | 'pageHeaderColor' | 'backgroundColor' | 'badgeColor'
function ColorSetting({ label, setting, fallback }: { label: string; setting: ColorPreference; fallback: string }) {
  const value = useDesignSettings((state) => state.preferences[setting])
  const update = useDesignSettings((state) => state.update)
  return (
    <div className="design-setting-row">
      <label>
        <span>{label}</span>
        <span className="design-color-picker">
          <input
            type="color"
            aria-label={label}
            value={value ?? fallback}
            onInput={(event) => update({ [setting]: event.currentTarget.value })}
            onChange={(event) => update({ [setting]: event.target.value })}
          />
          <span>{(value ?? fallback).toUpperCase()}</span>
        </span>
      </label>
      <button
        type="button"
        className="design-reset"
        disabled={value === null}
        aria-label={`${label} 복원`}
        onClick={() => update({ [setting]: null })}
      >
        복원
      </button>
    </div>
  )
}
function RangeSetting({
  label,
  setting,
  min,
  max,
  step,
  percent = false,
}: {
  label: string
  setting: 'glassOpacity' | 'glassBlur' | 'badgeOpacity' | 'tableHeaderLineOpacity'
  min: number
  max: number
  step: number
  percent?: boolean
}) {
  const value = useDesignSettings((state) => state.preferences[setting])
  const update = useDesignSettings((state) => state.update)
  return (
    <label className="design-setting-row">
      <span>
        {label}{' '}
        <output>
          {Math.round(value * (percent ? 100 : 1))}
          {percent ? '%' : 'px'}
        </output>
      </span>
      <input
        type="range"
        aria-label={label}
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(event) => update({ [setting]: Number(event.target.value) })}
      />
    </label>
  )
}
