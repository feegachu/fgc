(() => {
  "use strict";

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
