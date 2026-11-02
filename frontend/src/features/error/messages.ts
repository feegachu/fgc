import { internalErrorText } from '../../lib/format'

// messages.properties의 error.common.* 문구를 옮긴다. 403 문구는 authMessages(error.auth.*)를 쓴다.
export const errorMessages = {
  pageNotFound: '요청한 화면이 없습니다.',
  pageNotFoundHelp: '사이드바에서 회색으로 표시된 메뉴는 2차 범위라 아직 만들어지지 않았습니다.',
  // FgcMessageResolver처럼 {requestId}를 단순 치환한다(1차 templates/error/500.html). 다른 화면의 500 문구와 같은 함수다.
  internal: (requestId: string | null) => internalErrorText(requestId),
}
