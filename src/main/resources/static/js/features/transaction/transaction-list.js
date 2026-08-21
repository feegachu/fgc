// 2026-08-19 yslee - 체크리스트에서 진입한 귀속 불균형 필터 상태 유지
// 기존 코드: URL에서 읽은 attributionImbalanceOnly를 검색 폼 상태 재생성 시 누락
// 문제: 사용자가 검색 조건을 제출하면 불균형 전용 필터가 해제되어 전체 지급 건이 표시됨
// 개선: 검색 제출은 숨은 필터를 보존하고 명시적인 초기화에서만 해제하도록 상태 생성 분리
function buildTransactionFilterState(fields, page, attributionImbalanceOnly) {
  return {
    settlementMonth: fields.month.value,
    paymentStage: fields.stage.value,
    insurerId: fields.insurer.value,
    contractNo: fields.contractNo.value.trim(),
    sourceType: fields.source.value,
    agentId: fields.agent.value,
    commissionItemId: fields.item.value,
    status: fields.status.value,
    noAttributionOnly: fields.noAttribution.checked,
    attributionImbalanceOnly: attributionImbalanceOnly === true,
    page: page,
    size: 20
  };
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = { buildTransactionFilterState: buildTransactionFilterState };
}

(function () {
  "use strict";

  if (typeof window === "undefined" || typeof document === "undefined") return;

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var format = window.FgcUi && window.FgcUi.format;
  var form = document.querySelector("[data-transaction-filter-form]");
  var body = document.getElementById("list-body");
  var pagination = document.querySelector("[data-transaction-pagination]");
  var previousButton = document.querySelector("[data-transaction-page-prev]");
  var nextButton = document.querySelector("[data-transaction-page-next]");
  var currentPageText = document.querySelector("[data-transaction-current-page]");
  var totalPagesText = document.querySelector("[data-transaction-total-pages]");
  var pageNumbers = document.querySelector("[data-transaction-page-numbers]");
  var filterStatus = document.getElementById("transaction-filter-status");

  if (!apiClient || !format || !form || !body || !pagination || !previousButton || !nextButton) return;

  var fields = {
    month: document.getElementById("f-month"),
    stage: document.getElementById("f-stage"),
    insurer: document.getElementById("f-insurer"),
    contractNo: document.getElementById("f-contract-no"),
    source: document.getElementById("f-source"),
    agent: document.getElementById("f-agent"),
    item: document.getElementById("f-item"),
    status: document.getElementById("f-status"),
    noAttribution: document.getElementById("f-noattr")
  };
  var state = readState();
  var initialMonth = fields.month.value;
  var requestSequence = 0;
  var activeRequest = null;
  var knownAgents = {};
  var knownItems = {};

  addOptions(fields.source, [
    ["INSURER_STATEMENT", "원수사 명세"], ["GA_MANUAL_PAYMENT", "GA 수기지급"],
    ["GA_CONFIRMED_PAYMENT", "GA 확정지급"], ["ADJUSTMENT", "조정"],
    ["ALLOCATION_POOL", "공통비 풀"], ["CLAWBACK", "환수"], ["RECOVERY", "회수"]
  ]);
  addOptions(fields.status, [["DRAFT", "작성중"], ["CONFIRMED", "확정"], ["CANCELLED", "취소"]]);
  applyState();
  loadInsurers();

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    state = stateFromFields(1, state.attributionImbalanceOnly);
    updateUrl();
    load();
  });
  document.getElementById("f-reset").addEventListener("click", function (event) {
    event.preventDefault();
    form.reset();
    fields.month.value = initialMonth;
    state = stateFromFields(1, false);
    updateUrl();
    load();
  });
  previousButton.addEventListener("click", function () { changePage(pageGroupStart(state.page) - 1); });
  nextButton.addEventListener("click", function () { changePage(pageGroupStart(state.page) + 5); });
  window.addEventListener("resize", syncTableCellDisclosures);
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(syncTableCellDisclosures);

  load();

  function readState() {
    var params = new URLSearchParams(window.location.search);
    return {
      settlementMonth: params.get("settlementMonth") || "",
      paymentStage: params.get("paymentStage") || "",
      insurerId: params.get("insurerId") || "",
      contractNo: params.get("contractNo") || "",
      sourceType: params.get("sourceType") || "",
      agentId: params.get("agentId") || "",
      commissionItemId: params.get("commissionItemId") || "",
      status: params.get("status") || "",
      noAttributionOnly: params.get("noAttributionOnly") === "true",
      attributionImbalanceOnly: params.get("attributionImbalanceOnly") === "true",
      page: positivePage(params.get("page")),
      size: 20
    };
  }

  function applyState() {
    if (state.settlementMonth) fields.month.value = state.settlementMonth;
    fields.stage.value = state.paymentStage;
    fields.contractNo.value = state.contractNo;
    fields.source.value = state.sourceType;
    fields.status.value = state.status;
    fields.noAttribution.checked = state.noAttributionOnly;
  }

  function stateFromFields(page, attributionImbalanceOnly) {
    return buildTransactionFilterState(fields, page, attributionImbalanceOnly);
  }

  function load() {
    var sequence = ++requestSequence;
    if (activeRequest) activeRequest.abort();
    activeRequest = new AbortController();
    body.setAttribute("aria-busy", "true");
    renderState("loading", "불러오는 중", "수수료 지급 건을 불러오는 중입니다.");
    pagination.hidden = true;

    apiClient.request(apiPath(), { signal: activeRequest.signal })
      .then(function (envelope) {
        if (sequence !== requestSequence) return;
        var pageData = envelope && envelope.data;
        if (!pageData || !Array.isArray(pageData.content)) throw new Error("Invalid transaction page response");
        renderRows(pageData.content);
        renderPagination(pageData);
      })
      .catch(function (error) {
        if (error && error.name === "AbortError") return;
        if (sequence !== requestSequence) return;
        var message = format.errorText(error, "수수료 지급 건을 불러오지 못했습니다. 잠시 후 다시 시도하세요.");
        renderState("error", "목록을 불러오지 못했습니다", message, load);
        toast(message, "error");
        setText("row-count", 0);
        setText("sum-amount", format.won(0));
      })
      .finally(function () {
        if (sequence === requestSequence) body.setAttribute("aria-busy", "false");
      });
  }

  function apiPath() {
    var params = new URLSearchParams();
    Object.keys(state).forEach(function (key) {
      var value = state[key];
      if (value !== "" && value !== false && value != null) params.set(key, value);
    });
    return "/api/v1/transactions?" + params.toString();
  }

  function renderRows(rows) {
    clear(body);
    var sum = 0;
    if (rows.length === 0) renderState("empty", "조회 결과가 없습니다", "검색 조건을 바꾸어 다시 조회하세요.");

    rows.forEach(function (item) {
      rememberFilterOptions(item);
      sum += number(item.amount);
      var row = document.createElement("tr");
      appendCell(row, item.paymentStageLabel || item.paymentStage);
      appendDisclosureCell(row, item.sourceTypeLabel || item.sourceType);
      appendTitleCell(row, item.sourceBusinessKey, "transaction-mono");
      appendCell(row, format.month(item.settlementMonth));
      appendDisclosureCell(row, item.recipientAgentName || "-");
      appendDisclosureCell(row, item.commissionItemName || item.commissionItemCode);
      appendCell(row, format.won(item.amount), "is-number tabular-nums");
      appendBadgeCell(row, item.cashflowTypeLabel || item.cashflowType, cashflowBadge(item.cashflowType));
      appendBadgeCell(row, item.statusLabel || item.status, statusBadge(item.status));
      appendAttributionCell(row, item.attributionCount, item.differenceAmount);
      appendActionCell(row, item);
      body.appendChild(row);
    });

    refreshDynamicOptions();
    setText("row-count", rows.length);
      setText("sum-amount", format.won(sum));
    syncTableCellDisclosures();
  }

  function appendAttributionCell(row, count, difference) {
    var cell = document.createElement("td");
    var value = number(count);
    var imbalanced = value === 0 || number(difference) !== 0;
    var badge = document.createElement("span");
    badge.className = "status-badge " + (imbalanced ? "status-badge-error" : "status-badge-success");
    badge.textContent = imbalanced ? value + "건 · 확인필요" : value + "건 · 일치";
    cell.appendChild(badge);
    row.appendChild(cell);
  }

  function appendActionCell(row, item) {
    var cell = document.createElement("td");
    if (item.status === "DRAFT") {
      var link = document.createElement("a");
      link.className = "button button-ghost";
      link.href = "/transactions/new?id=" + encodeURIComponent(item.commissionTransactionId);
      link.textContent = "수정";
      link.setAttribute("aria-label", (item.sourceBusinessKey || "선택한 지급 건") + " 수정");
      cell.appendChild(link);
    } else {
      cell.textContent = "-";
    }
    row.appendChild(cell);
  }

  function renderPagination(pageData) {
    var totalPages = Math.max(number(pageData.totalPages), 1);
    var page = positivePage(pageData.page);
    setText("row-count", pageData.totalElements);
    currentPageText.textContent = page;
    totalPagesText.textContent = totalPages;
    renderPageNumbers(page, totalPages);
    previousButton.disabled = pageGroupStart(page) === 1;
    nextButton.disabled = pageGroupStart(page) + 5 > totalPages;
    previousButton.setAttribute("aria-disabled", String(previousButton.disabled));
    nextButton.setAttribute("aria-disabled", String(nextButton.disabled));
    pagination.hidden = number(pageData.totalElements) === 0;
  }

  function renderPageNumbers(page, totalPages) {
    pageNumbers.replaceChildren();
    var start = pageGroupStart(page);
    var end = Math.min(totalPages, start + 4);
    for (var current = start; current <= end; current += 1) {
      var button = document.createElement("button");
      button.type = "button";
      button.className = "pagination-button" + (current === page ? " is-active" : "");
      button.textContent = current;
      if (current === page) button.setAttribute("aria-current", "page");
      else (function (target) { button.addEventListener("click", function () { changePage(target); }); })(current);
      pageNumbers.appendChild(button);
    }
  }

  function pageGroupStart(page) {
    return Math.floor((page - 1) / 5) * 5 + 1;
  }

  function changePage(page) {
    if (page < 1) return;
    state.page = page;
    updateUrl();
    load();
  }

  function updateUrl() {
    var query = apiPath().split("?")[1];
    window.history.replaceState(null, "", window.location.pathname + (query ? "?" + query : ""));
  }

  function loadInsurers() {
    fields.insurer.disabled = true;
    apiClient.request("/api/v1/base/insurers?page=1&size=100")
      .then(function (envelope) {
        var rows = envelope && envelope.data && Array.isArray(envelope.data.content)
          ? envelope.data.content : [];
        clear(fields.insurer);
        addOption(fields.insurer, "", "전체");
        if (state.insurerId && !rows.some(function (row) {
          return String(row.insurerId) === String(state.insurerId);
        })) {
          addOption(fields.insurer, state.insurerId, state.insurerId);
        }
        rows.forEach(function (row) {
          addOption(fields.insurer, row.insurerId, row.insurerCode + " · " + row.insurerName);
        });
        fields.insurer.value = state.insurerId;
        fields.insurer.removeAttribute("aria-invalid");
        filterStatus.hidden = true;
      })
      .catch(function (error) {
        var message = format.errorText(error, "보험회사 목록을 불러오지 못했습니다. 잠시 후 화면을 새로고침하세요.");
        fields.insurer.setAttribute("aria-invalid", "true");
        filterStatus.textContent = message;
        filterStatus.hidden = false;
        toast(message, "error");
      })
      .finally(function () { fields.insurer.disabled = false; });
  }

  function rememberFilterOptions(item) {
    if (item.recipientAgentId != null && item.recipientAgentName) knownAgents[item.recipientAgentId] = item.recipientAgentName;
    if (item.commissionItemId != null && item.commissionItemName) knownItems[item.commissionItemId] = item.commissionItemName;
  }

  function refreshDynamicOptions() {
    rebuildOptions(fields.agent, knownAgents, state.agentId);
    rebuildOptions(fields.item, knownItems, state.commissionItemId);
  }

  function rebuildOptions(select, values, selected) {
    clear(select);
    addOption(select, "", "전체");
    if (selected && !Object.prototype.hasOwnProperty.call(values, selected)) {
      addOption(select, selected, selected);
    }
    Object.keys(values).sort(function (a, b) { return values[a].localeCompare(values[b], "ko"); })
      .forEach(function (key) { addOption(select, key, values[key]); });
    select.value = selected;
  }

  function addOptions(select, values) {
    values.forEach(function (entry) { addOption(select, entry[0], entry[1]); });
  }

  function addOption(select, optionValue, text) {
    var option = document.createElement("option");
    option.value = optionValue;
    option.textContent = text;
    select.appendChild(option);
  }

  function renderState(kind, title, message, retry) {
    clear(body);
    var row = document.createElement("tr");
    row.className = "transaction-state-row";
    var cell = document.createElement("td");
    var content = document.createElement("div");
    cell.colSpan = 11;
    content.className = "transaction-state transaction-state-" + kind;
    content.setAttribute("role", kind === "error" ? "alert" : "status");
    var icon = textElement("span", kind === "loading" ? "progress_activity" : kind === "error" ? "error" : "inbox");
    icon.className = "material-symbols-rounded transaction-state-icon";
    icon.setAttribute("aria-hidden", "true");
    content.appendChild(icon);
    var heading = textElement("strong", title);
    heading.className = "transaction-state-title";
    content.appendChild(heading);
    var copy = textElement("span", message);
    copy.className = "transaction-state-copy";
    content.appendChild(copy);
    if (retry) {
      var retryButton = textElement("button", "다시 시도");
      retryButton.type = "button";
      retryButton.className = "button button-secondary";
      retryButton.addEventListener("click", retry);
      content.appendChild(retryButton);
    }
    cell.appendChild(content);
    row.appendChild(cell);
    body.appendChild(row);
  }

  function appendBadgeCell(row, label, tone) {
    var cell = document.createElement("td");
    var badge = textElement("span", label || "-");
    badge.className = "status-badge " + tone;
    cell.appendChild(badge);
    row.appendChild(cell);
  }

  function cashflowBadge(value) { return value === "DEDUCTION" ? "status-badge-warning" : "status-badge-info"; }
  function statusBadge(value) {
    return { DRAFT: "status-badge-neutral", CONFIRMED: "status-badge-success", CANCELLED: "status-badge-error" }[value]
      || "status-badge-neutral";
  }

  function appendDisclosureCell(row, content, className) {
    var cell = document.createElement("td");
    cell.className = "table-cell-disclosure" + (className ? " " + className : "");
    var value = content == null || content === "" ? "-" : String(content);
    var preview = textElement("span", value);
    preview.className = "table-cell-preview is-single-line";
    var details = document.createElement("details");
    details.className = "table-cell-details";
    details.hidden = true;
    var summary = document.createElement("summary");
    summary.appendChild(textElement("span", "전체 보기"));
    summary.firstChild.className = "table-cell-more";
    var less = textElement("span", "접기");
    less.className = "table-cell-less";
    summary.appendChild(less);
    var chevron = textElement("span", "expand_more");
    chevron.className = "material-symbols-rounded table-cell-chevron";
    chevron.setAttribute("aria-hidden", "true");
    summary.appendChild(chevron);
    var full = textElement("div", value);
    full.className = "table-cell-full";
    details.appendChild(summary);
    details.appendChild(full);
    cell.appendChild(preview);
    cell.appendChild(details);
    row.appendChild(cell);
  }

  function syncTableCellDisclosures() {
    window.requestAnimationFrame(function () {
      body.querySelectorAll(".table-cell-disclosure").forEach(function (cell) {
        var preview = cell.querySelector(".table-cell-preview");
        var details = cell.querySelector(".table-cell-details");
        details.hidden = preview.scrollWidth <= preview.clientWidth && preview.scrollHeight <= preview.clientHeight;
      });
    });
  }

  function appendCell(row, content, className) {
    var cell = document.createElement("td");
    if (className) cell.className = className;
    cell.textContent = content == null || content === "" ? "-" : String(content);
    row.appendChild(cell);
  }

  // 업무키는 컬럼 폭을 넓게 잡고 title 툴팁으로 보완해 토글 없이 값을 보여준다(#326와 같은 방식).
  function appendTitleCell(row, content, className) {
    var cell = document.createElement("td");
    if (className) cell.className = className;
    var value = content == null || content === "" ? "-" : String(content);
    cell.textContent = value;
    cell.title = value;
    row.appendChild(cell);
  }

  function textElement(tag, value) { var element = document.createElement(tag); element.textContent = value; return element; }
  function toast(message, tone) { if (window.FgcUi && typeof window.FgcUi.toast === "function") window.FgcUi.toast(message, tone); }
  function number(value) { var parsed = Number(value); return Number.isFinite(parsed) ? parsed : 0; }
  function positivePage(value) { var parsed = Number.parseInt(value, 10); return Number.isInteger(parsed) && parsed > 0 ? parsed : 1; }
  function setText(id, value) { var element = document.getElementById(id); if (element) element.textContent = value; }
  function clear(element) { while (element.firstChild) element.removeChild(element.firstChild); }
})();
