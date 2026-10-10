// messages.properties의 error.common.* 문구를 옮긴다. 403 문구는 authMessages(error.auth.*)를 쓴다.
export const errorMessages = {
  pageNotFound: '요청한 화면이 없습니다.',
  pageNotFoundHelp: '사이드바에서 회색으로 표시된 메뉴는 2차 범위라 아직 만들어지지 않았습니다.',
  // FgcMessageResolver처럼 {requestId}를 단순 치환한다(1차 templates/error/500.html).
  internal: (requestId: string | null) =>
    `처리 중 오류가 발생했습니다. 요청번호 ${requestId ?? '-'}를 담당자에게 알려주세요.`,
}
