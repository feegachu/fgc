import { useState } from 'react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../lib/api/errors'
import { authMessages } from '../auth/messages'
import { errorMessages } from './messages'

// 1차 templates/error/{403,404,500,business}.html의 카드 구조·문구·"코드 · 요청 ID: 값" 형식을 따른다.
interface ErrorCardProps {
  heading: string
  message: string
  help?: ReactNode
  code?: string | null
  requestId?: string | null
  copyRequestId?: boolean
}
function ErrorCard({ heading, message, help, code, requestId, copyRequestId }: ErrorCardProps) {
  // 문구에 이미 요청 ID가 들어 있으면 다시 붙이지 않는다(format.ts errorText와 같은 규칙).
  const trace = [code, requestId && !message.includes(requestId) ? `요청 ID: ${requestId}` : null]
    .filter(Boolean)
    .join(' · ')
  return (
    <div className="error-page">
      <section className="surface error-card" aria-labelledby="error-title">
        <header className="surface-header">
          <h1 id="error-title" className="surface-title">{heading}</h1>
        </header>
        <div className="surface-body error-card-body">
          <p className="error-message" role="alert">{message}</p>
          {trace && <p className="error-trace tabular-nums">{trace}</p>}
          {help && <p className="error-help">{help}</p>}
          <div className="error-action">
            <Link className="button button-secondary" to="/">대시보드로</Link>
            {copyRequestId && requestId && <CopyButton value={requestId} />}
          </div>
        </div>
      </section>
    </div>
  )
}

function CopyButton({ value }: { value: string }) {
  const [label, setLabel] = useState('요청 ID 복사')
  // Clipboard API는 보안 컨텍스트(HTTPS·localhost)에서만 있다. 없으면 문구에서 직접 읽게 둔다.
  if (!navigator.clipboard) return null
  return (
    <button
      type="button"
      className="button button-ghost"
      aria-live="polite"
      onClick={() => {
        navigator.clipboard.writeText(value).then(
          () => setLabel('복사했습니다'),
          () => setLabel('복사하지 못했습니다'),
        )
      }}
    >
      {label}
    </button>
  )
}

// 라우트 권한 가드(RequirePermission)는 서버 요청 없이 막으므로 요청 ID가 없다. 코드는 Ajax 403과 같은
// FGC-AUTH-003을 보인다(SIR-007 규칙 3 — 같은 상황이면 같은 코드·문구). API 403이면 서버 값을 쓴다.
export function ForbiddenPage({ error }: { error?: ApiError }) {
  return (
    <ErrorCard
      heading="403 · 권한 없음"
      message={authMessages.forbidden}
      help={authMessages.forbiddenHelp}
      code={error?.code ?? 'FGC-AUTH-003'}
      requestId={error?.requestId}
    />
  )
}

// 경로 없음. 데이터 없음(상세 API 404)은 서버 문구를 그대로 쓰는 ApiErrorPage가 그린다.
export function NotFoundPage() {
  return <ErrorCard heading="404 · 찾을 수 없음" message={errorMessages.pageNotFound} help={errorMessages.pageNotFoundHelp} />
}

// 500 계열은 서버 문구 대신 요청번호만 보여 준다(인터페이스정의서 §3-1). 렌더링 예외는 요청번호가 없다.
export function ServerErrorPage({ error }: { error?: ApiError }) {
  const requestId = error?.requestId ?? null
  return (
    <ErrorCard
      heading="500 · 처리 오류"
      message={errorMessages.internal(requestId)}
      code={error?.code}
      requestId={requestId}
      copyRequestId
    />
  )
}

// 화면 분기는 오류 코드로 한다. 그 밖의 업무 오류는 1차 MpaExceptionHandler처럼 400이면 "입력 오류", 나머지는 "처리 오류".
export function ApiErrorPage({ error }: { error: ApiError }) {
  if (error.code === 'FGC-AUTH-003') return <ForbiddenPage error={error} />
  if (error.status >= 500) return <ServerErrorPage error={error} />
  const title = error.status === 400 ? '입력 오류' : '처리 오류'
  return (
    <ErrorCard
      heading={error.code === 'FGC-COMMON-004' ? '404 · 찾을 수 없음' : `${error.status} · ${title}`}
      message={error.message}
      help={typeof error.detail === 'string' ? error.detail : undefined}
      code={error.code}
      requestId={error.requestId}
    />
  )
}
