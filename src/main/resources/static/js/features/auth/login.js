(() => {
  "use strict";

  // 로그아웃 직후든 세션 만료든, 로그인 화면은 미인증 상태에서만 뜬다.
  // 이전 사용자의 UI 상태(워크스페이스 탭, 사이드바 펼침/접힘 등)가 다음 로그인까지
  // 남지 않도록 sessionStorage와 localStorage의 fgc.* 키를 전부 지운다.
  // 키를 하나씩 나열하면 새 키가 생길 때마다 여기도 같이 고쳐야 해서 접두사로 쓸어낸다.
  [sessionStorage, localStorage].forEach((storage) => {
    for (let i = storage.length - 1; i >= 0; i--) {
      const key = storage.key(i);
      if (key && key.startsWith("fgc.")) storage.removeItem(key);
    }
  });

  const form = document.querySelector("[data-login-form]");
  const passwordInput = document.querySelector("[data-password-input]");
  const passwordToggle = document.querySelector("[data-password-toggle]");
  const error = document.querySelector(".auth-error");
  const errorMessage = document.querySelector("[data-login-error-message]");

  if (passwordInput && passwordToggle) {
    passwordToggle.addEventListener("click", () => {
      const willShow = passwordInput.type === "password";
      passwordInput.type = willShow ? "text" : "password";
      passwordToggle.setAttribute("aria-pressed", String(willShow));
      passwordToggle.setAttribute("aria-label", willShow ? "비밀번호 숨기기" : "비밀번호 표시");

      const icon = passwordToggle.querySelector(".material-symbols-rounded");
      if (icon) {
        icon.textContent = willShow ? "visibility_off" : "visibility";
      }
    });
  }

  if (form) {
    form.addEventListener("submit", (event) => {
      if (form.checkValidity()) {
        return;
      }

      event.preventDefault();
      if (error && errorMessage) {
        errorMessage.textContent = "아이디와 비밀번호를 모두 입력해 주세요.";
        error.classList.add("is-visible");
      }

      const firstInvalid = form.querySelector(":invalid");
      if (firstInvalid) {
        firstInvalid.focus();
      }
    });

    form.addEventListener("input", () => {
      if (error && form.checkValidity()) {
        error.classList.remove("is-visible");
      }
    });
  }
})();
