import { useEffect, useId, useRef, useState } from 'react'
import { componentKinds, sidebarDesigns, useDesignSettings } from '../../stores/designSettings'
import type { DesignPreferences, SidebarDesign } from '../../stores/designSettings'
import './developer-settings.css'

export function DeveloperSettings({ sidebarColor }: { sidebarColor: string }) {
  const [open, setOpen] = useState(false)
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
        <section id={id} className="developer-settings-panel" aria-label="개발자 설정">
          <header className="design-panel-heading">
            <div>
              <span className="design-panel-eyebrow">DESIGN CONTROLS</span>
              <h2>화면 디자인 설정</h2>
              <p>설정을 바꾸면 현재 화면에 바로 반영됩니다.</p>
            </div>
            <button type="button" className="design-reset" onClick={reset}>
              기본값 복원
            </button>
          </header>
          <fieldset className="design-kpi-setting">
            <legend>대시보드 KPI 카드 통일</legend>
            <p>기존 대시보드 카드형을 기본으로 사용합니다. 원하는 버전을 선택하세요.</p>
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
                    onChange={() => update({ sidebarDesign: design.id, sidebarColor: null })}
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
              </select>
            </label>
            <div className="design-component-grid">
              {componentKinds.map((kind) => (
                <label key={kind.key}>
                  <span>{kind.label}</span>
                  <select
                    value={preferences[kind.key]}
                    onChange={(event) => update({ [kind.key]: event.target.value } as Partial<DesignPreferences>)}
                  >
                    <option value="inherit">전체 스타일 따름</option>
                    <option value="current">기존 디자인</option>
                    <option value="a">A안 / 표준 블루</option>
                    <option value="b">B안 / 라운드</option>
                    <option value="c">C안 / 컴팩트</option>
                  </select>
                </label>
              ))}
            </div>
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
