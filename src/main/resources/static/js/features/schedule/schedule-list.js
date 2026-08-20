(function () {
  "use strict";

  function positivePage(value) {
    var text = value == null ? "" : String(value);
    if (!/^[1-9]\d*$/.test(text)) return 1;

    var parsed = Number(text);
    return Number.isSafeInteger(parsed) ? parsed : 1;
  }

  function normalizePage(requestedPage, totalPages) {
    var parsedTotalPages = Number.parseInt(totalPages, 10);
    var lastPage = Number.isInteger(parsedTotalPages) && parsedTotalPages > 0 ? parsedTotalPages : 1;
    return Math.min(positivePage(requestedPage), lastPage);
  }

  /*
   * responseLabel 구현은 schedule-labels.js 한 곳에만 둔다 (#285).
   * 다만 src/test/js/schedule-list.test.cjs 가 이 파일에서 responseLabel 을 require 하므로
   * export 계약은 그대로 유지한다 — Node 에서는 형제 모듈을 직접 읽고, 브라우저에서는
   * 아래 window.FgcUi.scheduleLabels 를 쓴다. require 는 이 분기 안에서만 실행되므로
   * 브라우저에 require 가 없어도 안전하다.
   */
  if (typeof module === "object" && module.exports) {
    module.exports = {
      normalizePage: normalizePage,
      responseLabel: require("./schedule-labels.js").responseLabel
    };
    return;
  }

  var scheduleLabels = window.FgcUi && window.FgcUi.scheduleLabels;
  var format = window.FgcUi && window.FgcUi.format;
  if (!scheduleLabels || !format) return;
  var responseLabel = scheduleLabels.responseLabel;

  var form = document.querySelector("[data-schedule-filter-form]");
  var resetButton = document.querySelector("[data-schedule-filter-reset]");
  var table = document.querySelector("[data-schedule-table]");
  var tableBody = document.querySelector("[data-schedule-list-body]");
  var pagination = document.querySelector("[data-schedule-pagination]");
  var previousButton = document.querySelector("[data-schedule-page-prev]");
  var nextButton = document.querySelector("[data-schedule-page-next]");
  var currentPageText = document.querySelector("[data-schedule-current-page]");
  var totalPagesText = document.querySelector("[data-schedule-total-pages]");
  var pageNumbers = document.querySelector("[data-schedule-page-numbers]");
  var resultCount = document.querySelector("[data-schedule-result-count]");
  var pageSizeText = document.querySelector("[data-schedule-page-size]");
  var dataWarning = document.querySelector("[data-schedule-data-warning]");
  var exportButton = document.getElementById("btn-schedule-export");
  var apiClient = window.FgcUi && window.FgcUi.apiClient;

  if (!form || !table || !tableBody || !pagination || !previousButton || !nextButton) return;

  /*
   * CSV 내보내기 — 전에는 window.location.assign 만 호출해서 성공·실패 어느 쪽도 알리지 않았다.
   * fetch + Blob 으로 바꿔 두 경우 모두 Toast 를 띄운다. JS 가 죽어 있으면 아무 일도 없던
   * 예전과 같아지므로 회귀는 없다. 진행 중에는 aria-busy 로 스크린리더에도 알린다.
   */
  if (exportButton) {
    exportButton.addEventListener("click", function () {
      var parameters = new URLSearchParams(window.location.search);
      parameters.delete("page");
      parameters.delete("size");
      exportCsv("/api/v1/schedules/export.csv" + (parameters.size ? "?" + parameters.toString() : ""));
    });
  }

  function exportCsv(url) {
    exportButton.disabled = true;
    exportButton.setAttribute("aria-busy", "true");
    window.fetch(url, { credentials: "same-origin" })
      .then(function (response) {
        if (!response.ok) throw new Error("CSV 내보내기에 실패했습니다. (HTTP " + response.status + ")");
        return response.blob();
      })
      .then(function (blob) {
        var objectUrl = window.URL.createObjectURL(blob);
        var link = document.createElement("a");
        link.href = objectUrl;
        link.download = "schedules.csv";
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        window.URL.revokeObjectURL(objectUrl);
        toast("예상 스케줄 CSV를 내려받았습니다.", "success", 3500);
      })
      .catch(function (error) {
        toast(format.errorText(error, "CSV 내보내기에 실패했습니다."), "error", 5000);
      })
      .finally(function () {
        exportButton.disabled = false;
        exportButton.removeAttribute("aria-busy");
      });
  }

  function toast(message, tone, duration) {
    if (window.FgcUi && typeof window.FgcUi.toast === "function") {
      window.FgcUi.toast(message, tone, duration);
    }
  }

  var PAGE_SIZE = 20;
  var FILTER_NAMES = ["contractNo", "stage", "regime", "purpose", "status"];
  var ALLOWED = {
    stage: ["", "INSURER_TO_GA", "GA_TO_FC"],
    regime: ["", "CURRENT", "FOUR_YEAR_2027", "SEVEN_YEAR_2029", "TM_SPECIAL"],
    purpose: ["OPERATIONAL", "COMPARISON", "SIMULATION"],
    status: ["", "PLANNED", "CONFIRMED", "MATCHED", "ADJUSTED", "HOLD", "CANCELLED", "RESTARTED"]
  };
  /* 라벨·톤은 schedule-labels.js 한 벌만 쓴다 — SCHE-W02 와 같은 값을 보장한다. */
  var LABELS = {
    paymentStage: scheduleLabels.PAYMENT_STAGE,
    scheduleRegime: scheduleLabels.SCHEDULE_REGIME,
    schedulePurpose: scheduleLabels.SCHEDULE_PURPOSE,
    scheduleStatus: scheduleLabels.SCHEDULE_STATUS
  };
  var EMPTY = scheduleLabels.EMPTY;

  var activeRequest = null;
  var requestSequence = 0;
  var currentState = null;

  function allowedValue(name, value, fallback) {
    return ALLOWED[name].indexOf(value) >= 0 ? value : fallback;
  }

  function stateFromUrl() {
    var params = new URLSearchParams(window.location.search);
    return {
      contractNo: String(params.get("contractNo") || "").trim(),
      stage: allowedValue("stage", params.get("stage") || "", ""),
      regime: allowedValue("regime", params.get("regime") || "", ""),
      purpose: allowedValue("purpose", params.get("purpose") || "OPERATIONAL", "OPERATIONAL"),
      status: allowedValue("status", params.get("status") || "", ""),
      page: positivePage(params.get("page")),
      size: PAGE_SIZE
    };
  }

  function stateFromForm() {
    var values = new FormData(form);
    return {
      contractNo: String(values.get("contractNo") || "").trim(),
      stage: allowedValue("stage", String(values.get("stage") || ""), ""),
      regime: allowedValue("regime", String(values.get("regime") || ""), ""),
      purpose: allowedValue("purpose", String(values.get("purpose") || "OPERATIONAL"), "OPERATIONAL"),
      status: allowedValue("status", String(values.get("status") || ""), ""),
      page: 1,
      size: PAGE_SIZE
    };
  }

  function syncForm(state) {
    FILTER_NAMES.forEach(function (name) {
      if (form.elements[name]) form.elements[name].value = state[name];
    });
  }

  function apiPath(state) {
    var params = new URLSearchParams();
    FILTER_NAMES.forEach(function (name) {
      if (state[name]) params.set(name, state[name]);
    });
    params.set("page", String(state.page));
    params.set("size", String(state.size));
    return "/api/v1/schedules?" + params.toString();
  }

  function updateBrowserUrl(state, replace) {
    var url = new URL(window.location.href);
    FILTER_NAMES.forEach(function (name) { url.searchParams.delete(name); });
    url.searchParams.delete("page");
    url.searchParams.delete("size");

    FILTER_NAMES.forEach(function (name) {
      if (state[name]) url.searchParams.set(name, state[name]);
    });
    if (state.page > 1) url.searchParams.set("page", String(state.page));

    var nextUrl = url.pathname + (url.searchParams.toString() ? "?" + url.searchParams.toString() : "");
    window.history[replace ? "replaceState" : "pushState"]({}, "", nextUrl);
  }

  function clearBody() {
    while (tableBody.firstChild) tableBody.removeChild(tableBody.firstChild);
  }

  function makeStateRow(content, className) {
    var row = document.createElement("tr");
    row.className = "schedule-state-row";
    var cell = document.createElement("td");
    cell.colSpan = 11;
    var state = document.createElement("div");
    state.className = className;
    state.appendChild(content);
    cell.appendChild(state);
    row.appendChild(cell);
    tableBody.appendChild(row);
  }

  function renderLoading() {
    clearBody();
    table.setAttribute("aria-busy", "true");
    pagination.hidden = true;
    dataWarning.hidden = true;
    resultCount.textContent = EMPTY;

    for (var rowIndex = 0; rowIndex < 5; rowIndex += 1) {
      var row = document.createElement("tr");
      row.className = "schedule-loading-row";
      row.setAttribute("aria-hidden", "true");
      for (var columnIndex = 0; columnIndex < 11; columnIndex += 1) {
        var cell = document.createElement("td");
        var block = document.createElement("span");
        block.className = "schedule-loading-block";
        cell.appendChild(block);
        row.appendChild(cell);
      }
      tableBody.appendChild(row);
    }
  }

  function renderEmpty() {
    clearBody();
    var message = document.createElement("p");
    message.textContent = "조건에 맞는 예상 스케줄이 없습니다.";
    makeStateRow(message, "empty-state");
  }

  function renderError(error) {
    clearBody();
    pagination.hidden = true;
    dataWarning.hidden = true;
    resultCount.textContent = "0";

    var wrapper = document.createDocumentFragment();
    var title = document.createElement("strong");
    title.textContent = "예상 스케줄을 불러오지 못했습니다.";
    var message = document.createElement("p");
    message.textContent = error && error.message ? error.message : "잠시 후 다시 시도해 주세요.";
    if (error && error.requestId && message.textContent.indexOf(error.requestId) < 0) {
      message.textContent += " 요청 ID: " + error.requestId;
    }
    var retry = document.createElement("button");
    retry.type = "button";
    retry.className = "button button-secondary";
    retry.textContent = "다시 시도";
    retry.addEventListener("click", function () {
      load(currentState);
    });
    wrapper.append(title, message, retry);
    makeStateRow(wrapper, "schedule-error-state");
  }

  function isTrue(value) {
    return value === true || value === "true";
  }

  /*
   * 금액·건수는 공통 유틸만 쓴다 (FGC-SIR-008).
   * 전에는 이 파일이 직접 짠 정규식으로 콤마를 찍어서, 같은 도메인인 SCHE-W02 의
   * Intl 기반 포맷과 결과가 갈렸다. 음수 괄호 표기(화면정의서 4장 규칙 1)도 여기서만 빠져 있었다.
   */
  function textCell(value, className) {
    var cell = document.createElement("td");
    if (className) cell.className = className;
    cell.textContent = value === null || value === undefined || value === "" ? EMPTY : String(value);
    return cell;
  }

  function moneyCell(value) {
    var cell = textCell(format.won(value), "is-number tabular-nums");
    if (format.isNegative(value)) cell.classList.add("is-negative-amount");
    return cell;
  }

  function badge(value, tone, locked) {
    var element = document.createElement("span");
    element.className = "status-badge " + tone;
    element.appendChild(document.createTextNode(value));
    /* 규칙 8 확정 후 잠금 — 색이 아니라 아이콘으로도 확정을 알린다 (VRUN-W01 과 같은 표현). */
    if (locked) {
      var lock = document.createElement("span");
      lock.className = "material-symbols-rounded schedule-badge-lock";
      lock.setAttribute("aria-hidden", "true");
      lock.textContent = "lock";
      element.appendChild(lock);
    }
    return element;
  }

  function badgeCell(value, tone, locked) {
    var cell = document.createElement("td");
    cell.className = "is-center";
    cell.appendChild(badge(value, tone, locked));
    return cell;
  }

  /*
   * 긴 값 접기·펴기 — components.css:576-653 공통 구조.
   * 미리보기 자리에 노드를 그대로 넣을 수 있게 DOM 으로 만든다 (계약번호는 링크라서 필요하다).
   * 문자열을 조립하지 않으므로 escape 가 따로 필요 없다.
   */
  function disclosure(text, previewNode) {
    var value = text === null || text === undefined || text === "" ? EMPTY : String(text);
    var root = document.createElement("div");
    root.className = "table-cell-disclosure";

    var preview = document.createElement("span");
    preview.className = "table-cell-preview is-single-line";
    preview.appendChild(previewNode || document.createTextNode(value));
    root.appendChild(preview);

    var details = document.createElement("details");
    details.className = "table-cell-details";
    details.hidden = true;

    var summary = document.createElement("summary");
    var more = document.createElement("span");
    more.className = "table-cell-more";
    more.textContent = "전체 보기";
    var less = document.createElement("span");
    less.className = "table-cell-less";
    less.textContent = "접기";
    var chevron = document.createElement("span");
    chevron.className = "material-symbols-rounded table-cell-chevron";
    chevron.setAttribute("aria-hidden", "true");
    chevron.textContent = "expand_more";
    summary.append(more, less, chevron);

    var full = document.createElement("p");
    full.className = "table-cell-full";
    full.textContent = value;

    details.append(summary, full);
    root.appendChild(details);
    return root;
  }

  /* 실제 렌더링 폭을 재서 잘린 셀에만 "전체 보기" 를 남긴다. */
  function syncDisclosures() {
    tableBody.querySelectorAll(".table-cell-disclosure").forEach(function (root) {
      var preview = root.querySelector(".table-cell-preview");
      var details = root.querySelector(".table-cell-details");
      if (!preview || !details || preview.clientWidth === 0) return;
      var truncated = preview.scrollWidth > preview.clientWidth + 1
        || preview.scrollHeight > preview.clientHeight + 1;
      details.hidden = !truncated;
      if (!truncated) details.open = false;
    });
  }

  function detailHref(scheduleHeaderId) {
    return "/schedules/" + encodeURIComponent(String(scheduleHeaderId));
  }

  function renderRows(rows) {
    clearBody();

    rows.forEach(function (schedule) {
      var row = document.createElement("tr");
      var contractCell = document.createElement("td");
      var hasId = schedule.scheduleHeaderId !== null && schedule.scheduleHeaderId !== undefined;
      var contractNo = schedule.contractNo || EMPTY;

      if (hasId) {
        var contractLink = document.createElement("a");
        contractLink.href = detailHref(schedule.scheduleHeaderId);
        contractLink.className = "tabular-nums";
        contractLink.textContent = contractNo;
        contractCell.appendChild(disclosure(contractNo, contractLink));
      } else {
        contractCell.appendChild(disclosure(contractNo));
      }

      row.appendChild(contractCell);
      row.appendChild(textCell(responseLabel(schedule.paymentStageLabel, LABELS.paymentStage, schedule.paymentStage)));
      /* 적용 체계·용도는 상태가 아니라 분류값이다 — 규칙 4 의 상태 5색을 쓰지 않고 neutral 로 통일한다. */
      row.appendChild(badgeCell(responseLabel(schedule.scheduleRegimeLabel, LABELS.scheduleRegime, schedule.scheduleRegime), scheduleLabels.CLASSIFICATION_TONE));
      row.appendChild(badgeCell(responseLabel(schedule.schedulePurposeLabel, LABELS.schedulePurpose, schedule.schedulePurpose), scheduleLabels.CLASSIFICATION_TONE));
      row.appendChild(textCell(schedule.scheduleVersionNo === null || schedule.scheduleVersionNo === undefined ? EMPTY : "v" + schedule.scheduleVersionNo, "is-center tabular-nums"));
      row.appendChild(badgeCell(
        responseLabel(schedule.statusLabel, LABELS.scheduleStatus, schedule.status),
        scheduleLabels.statusTone(schedule.status),
        scheduleLabels.isLocked(schedule.status)));
      row.appendChild(badgeCell(isTrue(schedule.activeYn) ? "사용중" : "미사용", isTrue(schedule.activeYn) ? "status-badge-success" : "status-badge-neutral"));
      row.appendChild(textCell(format.int(schedule.lineCount), "is-number tabular-nums"));
      row.appendChild(moneyCell(schedule.expectedTotal));

      var policyCell = document.createElement("td");
      var policyValue = document.createElement("span");
      policyValue.className = "tabular-nums";
      policyValue.textContent = schedule.policyVersionLabel || EMPTY;
      policyCell.appendChild(disclosure(schedule.policyVersionLabel, policyValue));
      row.appendChild(policyCell);

      var actionCell = document.createElement("td");
      actionCell.className = "is-center";
      if (hasId) {
        var detailLink = document.createElement("a");
        detailLink.className = "button button-secondary schedule-row-button";
        detailLink.href = detailHref(schedule.scheduleHeaderId);
        detailLink.textContent = "회차 보기";
        detailLink.setAttribute("aria-label", (schedule.contractNo || "선택한 계약") + " 회차 보기");
        actionCell.appendChild(detailLink);
      } else {
        var disabledAction = document.createElement("span");
        disabledAction.className = "button button-secondary schedule-row-button is-disabled";
        disabledAction.setAttribute("aria-disabled", "true");
        disabledAction.textContent = "회차 보기";
        actionCell.appendChild(disabledAction);
      }
      row.appendChild(actionCell);
      tableBody.appendChild(row);
    });
  }

  function hasDuplicateActiveOperational(rows) {
    var activeKeys = Object.create(null);
    return rows.some(function (schedule) {
      if (!isTrue(schedule.activeYn) || schedule.schedulePurpose !== "OPERATIONAL") return false;
      var key = String(schedule.contractNo || "") + "|" + String(schedule.paymentStage || "");
      if (activeKeys[key]) return true;
      activeKeys[key] = true;
      return false;
    });
  }

  function setPageButton(button, disabled) {
    button.disabled = disabled;
    button.setAttribute("aria-disabled", String(disabled));
  }

  function renderPagination(pageData) {
    var totalPages = Number(pageData.totalPages) || 0;
    var displayedTotalPages = Math.max(1, totalPages);
    var page = Number(pageData.page) || 1;

    currentPageText.textContent = String(page);
    totalPagesText.textContent = String(displayedTotalPages);
    renderPageNumbers(page, displayedTotalPages);
    setPageButton(previousButton, pageGroupStart(page) === 1);
    setPageButton(nextButton, totalPages === 0 || pageGroupStart(page) + 5 > totalPages);
    pagination.hidden = false;
  }

  function renderPageNumbers(page, totalPages) {
    pageNumbers.replaceChildren();
    var start = pageGroupStart(page);
    var end = Math.min(totalPages, start + 4);
    for (var current = start; current <= end; current += 1) {
      var button = document.createElement("button");
      button.type = "button";
      button.className = "pagination-button" + (current === page ? " is-active" : "");
      button.textContent = String(current);
      if (current === page) button.setAttribute("aria-current", "page");
      else (function (target) {
        button.addEventListener("click", function () {
          currentState.page = target;
          updateBrowserUrl(currentState, false);
          load(currentState);
        });
      })(current);
      pageNumbers.appendChild(button);
    }
  }

  function pageGroupStart(page) {
    return Math.floor((page - 1) / 5) * 5 + 1;
  }

  function renderPage(pageData) {
    var rows = Array.isArray(pageData.content) ? pageData.content : [];
    resultCount.textContent = format.int(pageData.totalElements || 0);
    pageSizeText.textContent = String(pageData.size || PAGE_SIZE);
    dataWarning.hidden = !hasDuplicateActiveOperational(rows);

    if (rows.length === 0) renderEmpty();
    else renderRows(rows);

    renderPagination(pageData);
    table.setAttribute("aria-busy", "false");
    window.requestAnimationFrame(syncDisclosures);
  }

  function isPageResponse(value) {
    return value && typeof value === "object" && Array.isArray(value.content)
      && Number.isFinite(Number(value.page)) && Number.isFinite(Number(value.totalPages));
  }

  function load(state) {
    currentState = Object.assign({}, state);
    requestSequence += 1;
    var sequence = requestSequence;
    if (activeRequest) activeRequest.abort();
    activeRequest = new AbortController();
    renderLoading();

    if (!apiClient) {
      table.setAttribute("aria-busy", "false");
      renderError({ message: "공통 API Client를 불러오지 못했습니다." });
      return;
    }

    apiClient.request(apiPath(currentState), { signal: activeRequest.signal })
      .then(function (envelope) {
        if (sequence !== requestSequence) return;
        if (!isPageResponse(envelope.data)) throw new apiClient.ApiError(null, envelope.requestId, 200);
        var pageData = envelope.data;
        var normalizedPage = normalizePage(pageData.page, pageData.totalPages);
        pageData.page = normalizedPage;
        if (currentState.page !== normalizedPage) {
          currentState.page = normalizedPage;
          updateBrowserUrl(currentState, true);
          load(currentState);
          return;
        }
        renderPage(pageData);
      })
      .catch(function (error) {
        if (sequence !== requestSequence || error.name === "AbortError") return;
        table.setAttribute("aria-busy", "false");
        renderError(error);
      });
  }

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    var state = stateFromForm();
    updateBrowserUrl(state, false);
    load(state);
  });

  resetButton.addEventListener("click", function () {
    window.requestAnimationFrame(function () {
      var state = stateFromForm();
      updateBrowserUrl(state, false);
      load(state);
    });
  });

  previousButton.addEventListener("click", function () {
    if (!currentState) return;
    var previousGroupLastPage = pageGroupStart(currentState.page) - 1;
    if (previousGroupLastPage < 1) return;
    currentState.page = previousGroupLastPage;
    updateBrowserUrl(currentState, false);
    load(currentState);
  });

  nextButton.addEventListener("click", function () {
    if (!currentState || nextButton.disabled) return;
    currentState.page = pageGroupStart(currentState.page) + 5;
    updateBrowserUrl(currentState, false);
    load(currentState);
  });

  window.addEventListener("popstate", function () {
    var state = stateFromUrl();
    syncForm(state);
    load(state);
  });

  currentState = stateFromUrl();
  syncForm(currentState);
  load(currentState);
})();
