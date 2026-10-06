// messages.properties의 error.auth.*와 1차 login.js의 필수값 안내를 유지한다.
export const authMessages = {
  invalidCredentials: '아이디 또는 비밀번호가 맞지 않습니다.',
  sessionExpired: '로그인이 만료되었습니다. 다시 로그인하세요.',
  forbidden: '이 작업을 할 권한이 없습니다.',
  forbiddenHelp: '역할에 따라 볼 수 있는 화면과 누를 수 있는 버튼이 다릅니다. 필요한 권한은 GA관리자에게 요청하세요.',
  superseded: '다른 곳에서 같은 계정으로 로그인해 로그아웃되었습니다. 다시 로그인하세요.',
  required: '아이디와 비밀번호를 모두 입력해 주세요.',
  loggedOut: '로그아웃되었습니다.',
  logoutFailed: '이 기기의 로그인 정보는 지웠지만 서버 로그아웃을 완료하지 못했습니다. 다시 시도해 주세요.',
}
