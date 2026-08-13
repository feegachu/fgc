(() => {
  "use strict";

  // 로그아웃 직후든 세션 만료든, 로그인 화면은 미인증 상태에서만 뜬다.
  // 이전 세션의 워크스페이스 탭이 다음 로그인까지 남아있지 않도록 여기서 지운다.
  // (fgc.workspace-tabs.v1 은 common/workspace-tabs.js STORAGE_KEY와 같은 값)
  sessionStorage.removeItem("fgc.workspace-tabs.v1");

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
