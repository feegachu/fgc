import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Navigate, useNavigate, useSearchParams } from 'react-router'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { apiClient, ApiError } from '../../lib/api/client'
import { safeRedirect } from '../../lib/api/redirect'
import { authQueryOptions } from './api'
import { AuthStatus } from './AuthStatus'
import { authErrorText } from './errors'
import { authMessages } from './messages'
import { clearSessionData } from './session'
import { useAuth } from './useAuth'
import { useLogout } from './useLogout'

export function LoginPage() {
  const [search] = useSearchParams()
  const navigate = useNavigate()
  const client = useQueryClient()
  const auth = useAuth()
  const { logout, loggingOut } = useLogout()
  const [showPassword, setShowPassword] = useState(false)
  const [pending, setPending] = useState(false)
  const [loginRequested, setLoginRequested] = useState(false)
  const [error, setError] = useState(search.has('error') ? authMessages.invalidCredentials : '')
  const submitting = useRef(false)
  const notice = search.get('reason') === 'duplicate' ? authMessages.superseded
    : search.has('logout') ? (search.get('logout') === 'failed' ? authMessages.logoutFailed : authMessages.loggedOut)
    : search.has('redirect') ? authMessages.sessionExpired : ''

  useEffect(() => { document.title = '로그인 | FGC' }, [])

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const form = event.currentTarget
    if (!form.checkValidity()) {
      setError(authMessages.required)
      form.querySelector<HTMLInputElement>(':invalid')?.focus()
      return
    }
    const values = new FormData(form)
    submitting.current = true
    setPending(true)
    setLoginRequested(true)
    setError('')
    clearSessionData(client)
    try {
      await apiClient.login(String(values.get('username')), String(values.get('password')))
      await client.fetchQuery(authQueryOptions())
      // URLSearchParams가 이미 한 번 디코딩했으므로 다시 디코딩하지 않는다.
      await navigate(safeRedirect(search.get('redirect')), { replace: true })
    } catch (caught) {
      setError(caught instanceof ApiError && caught.code === 'FGC-AUTH-001'
        ? authMessages.invalidCredentials : authErrorText(caught))
    } finally {
      submitting.current = false
      setPending(false)
    }
  }

  if (loggingOut) return <AuthStatus />
  if (auth.user && !pending) return <Navigate to={loginRequested ? safeRedirect(search.get('redirect')) : '/'} replace />
  if (auth.accessToken && !pending) {
    return <AuthStatus error={auth.error} retry={() => {
      void auth.refetch().then((result) => {
        if (result.isSuccess) void navigate(safeRedirect(search.get('redirect')), { replace: true })
      })
    }} />
  }

  return (
    <main className="auth-page auth-layout">
      <aside className="auth-brand-panel" aria-label="FGC 플랫폼 소개">
        <div className="auth-brand-identity">
          <img className="auth-brand-logo" src={`${import.meta.env.BASE_URL}images/brand/logo-horizontal-white.png`} alt="FGC" width={120} height={50} />
          <p className="auth-brand-caption">GA 수수료 정산·검증 플랫폼</p>
        </div>
        <section className="auth-brand-message" aria-labelledby="auth-brand-title">
          <h1 id="auth-brand-title">정산 결과를 연결하고,<br />규제를 검증합니다.</h1>
          <p>계약·지급·대사·예외를 하나의 흐름으로 관리하고<br />모든 판정의 계산근거와 변경 이력을 끝까지 추적합니다.</p>
        </section>
        <footer className="auth-brand-footer"><p>접속 기록과 주요 변경 이력은 감사로그에 안전하게 남습니다.</p><p>FGC v2.0</p></footer>
      </aside>
      <section className="auth-surface" aria-labelledby="login-title">
        <div className="auth-login-content">
          <img className="auth-mobile-logo" src={`${import.meta.env.BASE_URL}images/brand/logo-horizontal-flat.png`} alt="FGC" width={96} height={40} />
          <div className="auth-heading-anchor" aria-hidden="true" />
          <header className="auth-login-header"><p className="auth-eyebrow">FGC WORKSPACE</p><h2 id="login-title">로그인</h2><p>업무 계정의 아이디와 비밀번호를 입력하세요.</p></header>
          {notice && <p className="auth-notice" role="status">{notice}</p>}
          {search.get('logout') === 'failed' && <Button variant="secondary" loading={loggingOut} onClick={() => void logout()}>로그아웃 다시 시도</Button>}
          {error && <div className="auth-error is-visible" role="alert"><span className="material-symbols-rounded" aria-hidden="true">error</span><span>{error}</span></div>}
          <form className="auth-form" onSubmit={(event) => void submit(event)} onInput={(event) => {
            if (event.currentTarget.checkValidity()) setError('')
          }} noValidate aria-busy={pending}>
            <Field label="아이디" required>
              <input className="auth-control" name="username" autoComplete="username" placeholder="업무 계정 아이디" autoFocus readOnly={pending} />
            </Field>
            <div className="auth-password-field">
              <Field label="비밀번호" required>
                <input className="auth-control" type={showPassword ? 'text' : 'password'} name="password" autoComplete="current-password" placeholder="비밀번호 입력" readOnly={pending} />
              </Field>
              <button className="icon-button auth-password-toggle" type="button" aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 표시'} aria-pressed={showPassword} onClick={() => setShowPassword(!showPassword)}>
                <span className="material-symbols-rounded" aria-hidden="true">{showPassword ? 'visibility_off' : 'visibility'}</span>
              </button>
            </div>
            <Button className="button-large auth-submit" type="submit" loading={pending}>로그인</Button>
          </form>
        </div>
      </section>
    </main>
  )
}
