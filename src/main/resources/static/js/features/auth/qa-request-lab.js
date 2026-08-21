(() => {
  "use strict";

  const lab = document.querySelector("[data-qa-request-lab]");
  if (!lab) return;

  const pathInput = lab.querySelector("[data-qa-path-input]");
  const bodyInput = lab.querySelector("[data-qa-body-input]");
  const includeCsrf = lab.querySelector("[data-qa-include-csrf]");
  const result = lab.querySelector("[data-qa-result]");
  const methodButtons = Array.from(lab.querySelectorAll("[data-qa-method]"));

  function show(message) {
    result.textContent = message;
  }

  function csrfHeader() {
    const token = document.querySelector("meta[name='_csrf']");
    const header = document.querySelector("meta[name='_csrf_header']");
    if (!token || !header) return null;
    return { name: header.content, value: token.content };
  }

  function requestUrl() {
    const url = new URL(pathInput.value.trim(), window.location.origin);
    if (url.origin !== window.location.origin || !url.pathname.startsWith("/api/")) {
      throw new Error("같은 서버의 /api/** 경로만 요청할 수 있습니다.");
    }
    return url;
  }

  async function send(method) {
    let url;
    try {
      url = requestUrl();
    } catch (error) {
      show(`입력 오류: ${error.message}`);
      pathInput.focus();
      return;
    }

    const bodyText = bodyInput.value.trim();
    let body;
    if (method !== "GET" && bodyText) {
      try {
        body = JSON.stringify(JSON.parse(bodyText));
      } catch (error) {
        show(`JSON 오류: ${error.message}`);
        bodyInput.focus();
        return;
      }
    }

    if (method !== "GET" && !window.confirm(`${method} ${url.pathname}${url.search}\n\n테스트 DB에 실제 변경 요청을 보냅니다.`)) {
      return;
    }

    const headers = new Headers({ Accept: "application/json" });
    if (body !== undefined) headers.set("Content-Type", "application/json");
    if (includeCsrf.checked) {
      const csrf = csrfHeader();
      if (csrf) headers.set(csrf.name, csrf.value);
    }

    methodButtons.forEach((button) => { button.disabled = true; });
    lab.setAttribute("aria-busy", "true");
    show(`${method} ${url.pathname}${url.search}\n요청 중...`);

    try {
      const response = await fetch(url.pathname + url.search, {
        method,
        headers,
        body,
        credentials: "same-origin"
      });
      const responseBody = await response.text();
      const summary = [
        `${method} ${url.pathname}${url.search}`,
        `상태: ${response.status} ${response.statusText}`,
        `Content-Type: ${response.headers.get("Content-Type") || "-"}`,
        `X-Request-Id: ${response.headers.get("X-Request-Id") || "-"}`,
        `최종 URL: ${response.url}`,
        "",
        responseBody || "(응답 본문 없음)"
      ];
      show(summary.join("\n"));
    } catch (error) {
      show(`${method} ${url.pathname}${url.search}\n네트워크 오류: ${error.message}`);
    } finally {
      methodButtons.forEach((button) => { button.disabled = false; });
      lab.removeAttribute("aria-busy");
    }
  }

  lab.querySelectorAll("[data-qa-path]").forEach((button) => {
    button.addEventListener("click", () => {
      pathInput.value = button.dataset.qaPath;
      pathInput.focus();
    });
  });

  methodButtons.forEach((button) => {
    button.addEventListener("click", () => send(button.dataset.qaMethod));
  });
})();
