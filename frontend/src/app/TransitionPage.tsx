import { useLocation } from 'react-router'
import { legacyHref } from './screens'
import type { ScreenDefinition } from './screens'
export function TransitionPage({ screen }: { screen: ScreenDefinition }) {
  const location = useLocation()
  return (
    <>
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">{screen.title}</h1>
          <p className="page-description">{screen.id}</p>
        </div>
      </header>
      <section className="react-transition">
        <h2>전환 중 — 기존 화면으로 이동</h2>
        <p>이 업무 화면은 현재 React로 전환하고 있습니다.</p>
        <a className="button button-secondary" href={legacyHref(screen, location.pathname, location.search)}>
          기존 화면으로 이동
        </a>
      </section>
    </>
  )
}
