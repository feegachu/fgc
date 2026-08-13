(function () {
  "use strict";

  var form = document.querySelector("[data-schedule-filter-form]");
  var resetButton = document.querySelector("[data-schedule-filter-reset]");
  var table = document.querySelector("[data-schedule-table]");
  var tableBody = document.querySelector("[data-schedule-list-body]");
  var pagination = document.querySelector("[data-schedule-pagination]");
  var previousButton = document.querySelector("[data-schedule-page-prev]");
  var nextButton = document.querySelector("[data-schedule-page-next]");
  var currentPageText = document.querySelector("[data-schedule-current-page]");
  var totalPagesText = document.querySelector("[data-schedule-total-pages]");
  var pageNumberText = document.querySelector("[data-schedule-page-number]");
  var resultCount = document.querySelector("[data-schedule-result-count]");
  var pageSizeText = document.querySelector("[data-schedule-page-size]");
  var dataWarning = document.querySelector("[data-schedule-data-warning]");
  var apiClient = window.FgcUi && window.FgcUi.apiClient;

  if (!form || !table || !tableBody || !pagination || !previousButton || !nextButton) return;

  var PAGE_SIZE = 20;
  var FILTER_NAMES = ["contractNo", "stage", "regime", "purpose", "status"];
  var ALLOWED = {
    stage: ["", "INSURER_TO_GA", "GA_TO_FC"],
    regime: ["", "CURRENT", "FOUR_YEAR_2027", "SEVEN_YEAR_2029", "TM_SPECIAL"],
    purpose: ["OPERATIONAL", "COMPARISON", "SIMULATION"],
    status: ["", "PLANNED", "CONFIRMED", "MATCHED", "ADJUSTED", "HOLD", "CANCELLED", "RESTARTED"]
  };
  var LABELS = {
    paymentStage: {
      INSURER_TO_GA: "원수사→GA",
      GA_TO_FC: "GA→설계사"
    },
    scheduleRegime: {
      CURRENT: "현행",
      FOUR_YEAR_2027: "4년 분급(2027)",
      SEVEN_YEAR_2029: "7년 분급(2029)",
      TM_SPECIAL: "TM 특례"
    },
    schedulePurpose: {
      OPERATIONAL: "운영",
      COMPARISON: "비교",
      SIMULATION: "시뮬레이션"
    },
    scheduleStatus: {
      PLANNED: "예정",
      CONFIRMED: "확정",
      MATCHED: "대사일치",
      ADJUSTED: "조정완료",
      HOLD: "보류",
      CANCELLED: "취소",
      RESTARTED: "재개"
    }
  };
  var STATUS_TONES = {
    PLANNED: "status-badge-info",
    CONFIRMED: "status-badge-review",
    MATCHED: "status-badge-success",
    ADJUSTED: "status-badge-warning",
    HOLD: "status-badge-risk",
    CANCELLED: "status-badge-error",
    RESTARTED: "status-badge-info"
  };

  var activeRequest = null;
  var requestSequence = 0;
  var currentState = null;

  function allowedValue(name, value, fallback) {
    return ALLOWED[name].indexOf(value) >= 0 ? value : fallback;
  }

  function positivePage(value) {
    var parsed = Number.parseInt(value, 10);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : 1;
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
    resultCount.textContent = "—";

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
    retry.addEventListener("click", function () { load(currentState); });
    wrapper.append(title, message, retry);
    makeStateRow(wrapper, "schedule-error-state");
  }

  function label(group, value) {
    return LABELS[group][value] || value || "-";
  }

  function isTrue(value) {
    return value === true || value === "true";
  }

  function formatInteger(value) {
    if (value === null || value === undefined || value === "") return "-";
    var raw = String(value).trim();
    if (!/^-?\d+$/.test(raw)) return raw;
    var sign = raw.charAt(0) === "-" ? "-" : "";
    var digits = sign ? raw.slice(1) : raw;
    return sign + digits.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  }

  function formatWon(value) {
    if (value === null || value === undefined || value === "") return "-";
    var raw = String(value).trim();
    var match = raw.match(/^(-?)(\d+)(?:\.(\d+))?$/);
    if (!match) return raw;
    var integer = match[2].replace(/\B(?=(\d{3})+(?!\d))/g, ",");
    var fraction = match[3] && /[1-9]/.test(match[3]) ? "." + match[3] : "";
    return match[1] + integer + fraction + "원";
  }

  function textCell(value, className) {
    var cell = document.createElement("td");
    if (className) cell.className = className;
    cell.textContent = value === null || value === undefined || value === "" ? "-" : String(value);
    return cell;
  }

  function badge(value, tone) {
    var element = document.createElement("span");
    element.className = "status-badge " + tone;
    element.textContent = value;
    return element;
  }

  function badgeCell(value, tone) {
    var cell = document.createElement("td");
    cell.className = "is-center";
    cell.appendChild(badge(value, tone));
    return cell;
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

      if (hasId) {
        var contractLink = document.createElement("a");
        contractLink.href = detailHref(schedule.scheduleHeaderId);
        contractLink.className = "tabular-nums";
        contractLink.textContent = schedule.contractNo || "-";
        contractCell.appendChild(contractLink);
      } else {
        contractCell.textContent = schedule.contractNo || "-";
      }

      row.appendChild(contractCell);
      row.appendChild(textCell(label("paymentStage", schedule.paymentStage)));
      row.appendChild(badgeCell(label("scheduleRegime", schedule.scheduleRegime), "status-badge-neutral"));
      row.appendChild(badgeCell(label("schedulePurpose", schedule.schedulePurpose), schedule.schedulePurpose === "OPERATIONAL" ? "status-badge-info" : "status-badge-neutral"));
      row.appendChild(textCell(schedule.scheduleVersionNo === null || schedule.scheduleVersionNo === undefined ? "-" : "v" + schedule.scheduleVersionNo, "is-center tabular-nums"));
      row.appendChild(badgeCell(label("scheduleStatus", schedule.status), STATUS_TONES[schedule.status] || "status-badge-neutral"));
      row.appendChild(badgeCell(isTrue(schedule.activeYn) ? "사용중" : "미사용", isTrue(schedule.activeYn) ? "status-badge-success" : "status-badge-neutral"));
      row.appendChild(textCell(formatInteger(schedule.lineCount), "is-number tabular-nums"));
      row.appendChild(textCell(formatWon(schedule.expectedTotal), "is-number tabular-nums"));

      var policyCell = document.createElement("td");
      var policyValue = document.createElement("span");
      policyValue.className = "schedule-policy-value tabular-nums";
      policyValue.textContent = schedule.policyVersionLabel || "-";
      if (schedule.policyVersionLabel) policyValue.title = schedule.policyVersionLabel;
      policyCell.appendChild(policyValue);
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
    pageNumberText.textContent = String(page);
    setPageButton(previousButton, page <= 1);
    setPageButton(nextButton, totalPages === 0 || page >= totalPages);
    pagination.hidden = false;
  }

  function renderPage(pageData) {
    var rows = Array.isArray(pageData.content) ? pageData.content : [];
    resultCount.textContent = formatInteger(pageData.totalElements || 0);
    pageSizeText.textContent = String(pageData.size || PAGE_SIZE);
    dataWarning.hidden = !hasDuplicateActiveOperational(rows);

    if (rows.length === 0) renderEmpty();
    else renderRows(rows);

    renderPagination(pageData);
    table.setAttribute("aria-busy", "false");
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
        if (pageData.totalPages > 0 && pageData.page > pageData.totalPages) {
          currentState.page = pageData.totalPages;
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
    if (!currentState || currentState.page <= 1) return;
    currentState.page -= 1;
    updateBrowserUrl(currentState, false);
    load(currentState);
  });

  nextButton.addEventListener("click", function () {
    if (!currentState || nextButton.disabled) return;
    currentState.page += 1;
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
