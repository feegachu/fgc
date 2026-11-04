import { internalErrorText } from '../../lib/format'

// messages.properties의 error.common.* 문구를 옮긴다. 403 문구는 authMessages(error.auth.*)를 쓴다.
export const errorMessages = {
  pageNotFound: '요청한 화면이 없습니다.',
  pageNotFoundHelp: '주소를 다시 확인하거나 왼쪽 메뉴에서 화면을 고르세요.',
  // FgcMessageResolver처럼 {requestId}를 단순 치환한다(1차 templates/error/500.html). 다른 화면의 500 문구와 같은 함수다.
  internal: (requestId: string | null) => internalErrorText(requestId),
}
