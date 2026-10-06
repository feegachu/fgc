import { Link, useLocation, useOutletContext } from 'react-router'
import type { ShellContext } from './shell/AppShell'
import { legacyHref, screenId } from './screens'
import type { ScreenDefinition } from './screens'
export function TransitionPage({ screen }: { screen: ScreenDefinition }) {
  const location = useLocation()
  const { user } = useOutletContext<ShellContext>()
  if (screen.permission && !user[screen.permission])
    return (
      <section className="react-transition">
        <h1 className="page-title">접근 권한이 없습니다.</h1>
        <p>현재 역할로 이 화면을 사용할 수 없습니다.</p>
        <Link to="/">업무 대시보드로 이동</Link>
      </section>
    )
  return (
    <>
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">{screen.title}</h1>
          <p className="page-description">{screenId(screen)}</p>
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
export function LoginPlaceholder() {
  return (
    <main className="page-content">
      <h1 className="page-title">로그인 화면 전환 중</h1>
      <p>새 로그인 화면을 준비하고 있습니다.</p>
      <a className="button button-primary" href="/login">
        기존 로그인 화면으로 이동
      </a>
    </main>
  )
}
