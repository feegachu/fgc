import { isRouteErrorResponse, useRouteError } from 'react-router'
import { ApiError } from '../lib/api/errors'
import { ApiErrorPage, NotFoundPage, ServerErrorPage } from '../features/error/ErrorPages'

// 라우트 errorElement. 렌더링 예외·지연 청크 로드 실패·화면이 던진 ApiError를 1차 오류 화면 형식으로 보인다.
// AppShell 안쪽에 두면 셸·탭은 그대로이고, 다른 경로로 이동하면 React Router가 오류 상태를 지운다.
// 예외 메시지·스택은 내부 정보라 화면에 내지 않는다(React Router가 콘솔에 남긴다).
export function RouteErrorBoundary() {
  const error = useRouteError()
  if (error instanceof ApiError) return <ApiErrorPage error={error} />
  if (isRouteErrorResponse(error) && error.status === 404) return <NotFoundPage />
  return <ServerErrorPage />
}
