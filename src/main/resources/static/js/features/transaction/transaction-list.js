(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var form = document.querySelector("[data-transaction-filter-form]");
  var body = document.getElementById("list-body");
  var pagination = document.querySelector("[data-transaction-pagination]");
  var previousButton = document.querySelector("[data-transaction-page-prev]");
  var nextButton = document.querySelector("[data-transaction-page-next]");
  var currentPageText = document.querySelector("[data-transaction-current-page]");
  var totalPagesText = document.querySelector("[data-transaction-total-pages]");
  var pageNumberText = document.querySelector("[data-transaction-page-number]");

  if (!apiClient || !form || !body || !pagination || !previousButton || !nextButton) return;

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
    state = stateFromFields(1);
    updateUrl();
    load();
  });
  document.getElementById("f-reset").addEventListener("click", function (event) {
    event.preventDefault();
    form.reset();
    fields.month.value = initialMonth;
    state = stateFromFields(1);
    updateUrl();
    load();
  });
  previousButton.addEventListener("click", function () { changePage(state.page - 1); });
  nextButton.addEventListener("click", function () { changePage(state.page + 1); });

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

  function stateFromFields(page) {
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
      page: page,
      size: 20
    };
  }

  function load() {
    var sequence = ++requestSequence;
    if (activeRequest) activeRequest.abort();
    activeRequest = new AbortController();
    renderMessage("수수료 지급 건을 불러오는 중입니다.", false);
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
        var message = error && error.message ? error.message : "수수료 지급 건을 불러오지 못했습니다.";
        if (error && error.requestId) message += " 요청번호 " + error.requestId;
        renderMessage(message, true);
        setText("row-count", 0);
        setText("sum-amount", formatMoney(0));
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
    if (rows.length === 0) renderMessage("조건에 맞는 자료가 없습니다.", false);

    rows.forEach(function (item) {
      rememberFilterOptions(item);
      sum += number(item.amount);
      var row = document.createElement("tr");
      appendCell(row, item.paymentStageLabel || item.paymentStage);
      appendCell(row, item.sourceTypeLabel || item.sourceType);
      appendCell(row, item.sourceBusinessKey, "fgc-mono");
      appendCell(row, formatMonth(item.settlementMonth));
      appendCell(row, item.recipientAgentName || "—");
      appendCell(row, item.commissionItemName || item.commissionItemCode);
      appendCell(row, formatMoney(item.amount), "fgc-td-num");
      appendCell(row, item.cashflowTypeLabel || item.cashflowType);
      appendCell(row, item.statusLabel || item.status);
      appendAttributionCell(row, item.attributionCount, item.differenceAmount);
      appendActionCell(row, item);
      body.appendChild(row);
    });

    refreshDynamicOptions();
    setText("row-count", rows.length);
    setText("sum-amount", formatMoney(sum));
  }

  function appendAttributionCell(row, count, difference) {
    var cell = document.createElement("td");
    var value = number(count);
    cell.textContent = value + "건";
    if (value === 0 || number(difference) !== 0) {
      cell.style.color = "#d92d20";
      cell.style.fontWeight = "700";
    }
    row.appendChild(cell);
  }

  function appendActionCell(row, item) {
    var cell = document.createElement("td");
    if (item.status === "DRAFT") {
      var link = document.createElement("a");
      link.className = "fgc-btn fgc-btn--ghost";
      link.href = "/transactions/new?id=" + encodeURIComponent(item.commissionTransactionId);
      link.textContent = "수정";
      cell.appendChild(link);
    } else {
      cell.textContent = "—";
    }
    row.appendChild(cell);
  }

  function renderPagination(pageData) {
    var totalPages = Math.max(number(pageData.totalPages), 1);
    var page = positivePage(pageData.page);
    setText("row-count", pageData.totalElements);
    currentPageText.textContent = page;
    totalPagesText.textContent = totalPages;
    pageNumberText.textContent = page;
    previousButton.disabled = page <= 1;
    nextButton.disabled = page >= totalPages;
    pagination.hidden = number(pageData.totalElements) === 0;
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
      })
      .catch(function () {
        fields.insurer.title = "보험회사 목록을 불러오지 못했습니다.";
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

  function renderMessage(message, error) {
    clear(body);
    var row = document.createElement("tr");
    var cell = document.createElement("td");
    var content = document.createElement("div");
    cell.colSpan = 11;
    content.className = "fgc-empty";
    content.textContent = message;
    if (error) content.style.color = "#d92d20";
    cell.appendChild(content);
    row.appendChild(cell);
    body.appendChild(row);
  }

  function appendCell(row, content, className) {
    var cell = document.createElement("td");
    if (className) cell.className = className;
    cell.textContent = content == null || content === "" ? "—" : String(content);
    row.appendChild(cell);
  }

  function formatMonth(value) { return value ? String(value).slice(0, 7) : "—"; }
  function formatMoney(value) { return new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 }).format(number(value)) + "원"; }
  function number(value) { var parsed = Number(value); return Number.isFinite(parsed) ? parsed : 0; }
  function positivePage(value) { var parsed = Number.parseInt(value, 10); return Number.isInteger(parsed) && parsed > 0 ? parsed : 1; }
  function setText(id, value) { var element = document.getElementById(id); if (element) element.textContent = value; }
  function clear(element) { while (element.firstChild) element.removeChild(element.firstChild); }
})();
