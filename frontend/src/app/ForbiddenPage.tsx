import { Link } from 'react-router'
import { authMessages } from '../features/auth/messages'

// #417의 공통 오류 화면으로 교체할 수 있는 403 연결점.
export function ForbiddenPage() {
  return (
    <section className="react-transition" aria-labelledby="forbidden-title">
      <p>403</p>
      <h1 id="forbidden-title" className="page-title">접근 권한이 없습니다.</h1>
      <p role="alert">{authMessages.forbidden}</p>
      <p>{authMessages.forbiddenHelp}</p>
      <Link to="/">업무 대시보드로 이동</Link>
    </section>
  )
}
