(function () {
  "use strict";

  function ApiError(error, requestId, status) {
    this.name = "ApiError";
    this.code = error && error.code ? error.code : "FGC-COMMON-500";
    this.message = error && error.message ? error.message : "요청을 처리하지 못했습니다.";
    this.field = error && error.field ? error.field : null;
    this.params = error && error.params ? error.params : {};
    this.requestId = requestId || null;
    this.status = status;
  }
  ApiError.prototype = Object.create(Error.prototype);
  ApiError.prototype.constructor = ApiError;

  function csrfHeader() {
    var token = document.querySelector("meta[name='_csrf']");
    var header = document.querySelector("meta[name='_csrf_header']");
    if (!token || !header) return null;
    return { name: header.content, value: token.content };
  }

  function request(path, options) {
    var requestOptions = options || {};
    var headers = new Headers(requestOptions.headers || {});
    headers.set("Accept", "application/json");
    if (requestOptions.body !== undefined && !(requestOptions.body instanceof FormData)) {
      headers.set("Content-Type", "application/json");
    }
    var csrf = csrfHeader();
    if (csrf && requestOptions.method && requestOptions.method !== "GET") headers.set(csrf.name, csrf.value);
    if (requestOptions.idempotencyKey) headers.set("Idempotency-Key", requestOptions.idempotencyKey);

    return fetch(path, {
      method: requestOptions.method || "GET",
      headers: headers,
      body: requestOptions.body === undefined || requestOptions.body instanceof FormData
        ? requestOptions.body
        : JSON.stringify(requestOptions.body),
      credentials: "same-origin",
      signal: requestOptions.signal
    }).then(function (response) {
      if (response.status === 401) {
        window.location.assign("/login");
        throw new ApiError({ code: "FGC-AUTH-002", message: "로그인이 만료되었습니다. 다시 로그인하세요." }, null, 401);
      }
      if (response.status === 204) return { data: null, error: null, requestId: response.headers.get("X-Request-Id") };
      return response.json().catch(function () {
        throw new ApiError(null, response.headers.get("X-Request-Id"), response.status);
      }).then(function (envelope) {
        if (envelope === null || typeof envelope !== "object") {
          throw new ApiError(null, response.headers.get("X-Request-Id"), response.status);
        }
        if (!response.ok || envelope.error) throw new ApiError(envelope.error, envelope.requestId, response.status);
        return envelope;
      });
    });
  }

  window.FgcUi = window.FgcUi || {};
  window.FgcUi.apiClient = { request: request, ApiError: ApiError };
})();
