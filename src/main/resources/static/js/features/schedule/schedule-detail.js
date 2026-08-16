(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var main = document.getElementById("main-content");
  var lineBody = document.getElementById("line-body");

  if (!main || !lineBody || !apiClient) return;

  var scheduleHeaderId = main.dataset.scheduleHeaderId || idFromPath();
  if (!scheduleHeaderId) {
    renderError("스케줄 ID를 확인할 수 없습니다.");
    return;
  }

  load();

  function idFromPath() {
    var matched = window.location.pathname.match(/^\/schedules\/(\d+)\/?$/);
    return matched ? matched[1] : null;
  }

  function load() {
    renderLoading();
    apiClient.request("/api/v1/schedules/" + encodeURIComponent(scheduleHeaderId))
      .then(function (envelope) {
        var detail = envelope && envelope.data;
        if (!detail || !detail.header) throw new Error("Invalid schedule detail response");
        renderHeader(detail.header);
        renderLines(Array.isArray(detail.schedules) ? detail.schedules : []);
      })
      .catch(function (error) {
        console.error(error);
        var message = error && error.message
          ? error.message
          : "예상 스케줄을 불러오지 못했습니다.";
        if (error && error.requestId) message += " 요청번호 " + error.requestId;
        renderError(message);
      });
  }

  function renderHeader(header) {
    setText("hdr-id", "schedule_header #" + value(header.scheduleHeaderId));
    setText("hdr-contract", header.contractNo);
    setText("hdr-product", "—");
    setText("hdr-stage", label({ INSURER_TO_GA: "원수사→GA", GA_TO_FC: "GA→설계사" }, header.paymentStage));
    setText("hdr-regime", label({ CURRENT: "현행", FOUR_YEAR: "4년 분급", SEVEN_YEAR: "7년 분급" }, header.scheduleRegime));
    setText("hdr-purpose", label({ OPERATIONAL: "운영", SIMULATION: "비교·시뮬레이션" }, header.schedulePurpose));
    setText("hdr-version", header.scheduleVersionNo == null ? "—" : "v" + header.scheduleVersionNo);
    setText("hdr-status", label({ PLANNED: "예정", CONFIRMED: "확정", SUPERSEDED: "대체됨", CANCELLED: "취소" }, header.status));
    setText("hdr-active", header.activeYn === true ? "사용중" : "미사용");
    setText("hdr-policy", header.policyVersionLabel);
    setText("hdr-reason", join(header.generationReason, formatDateTime(header.generatedAt)));

    var stage = document.getElementById("stage");
    if (stage && header.paymentStage) {
      stage.value = header.paymentStage;
      stage.disabled = true;
    }
  }

  function renderLines(lines) {
    clear(lineBody);
    if (lines.length === 0) {
      appendMessage("조건에 맞는 자료가 없습니다.", false);
      updateTotals(0, 0, 0);
      return;
    }

    var total = 0;
    var firstYear = 0;
    lines.forEach(function (line) {
      var amount = number(line.expectedAmount);
      total += amount;
      if (number(line.contractMonthNo) >= 1 && number(line.contractMonthNo) <= 12) firstYear += amount;

      var row = document.createElement("tr");
      appendCell(row, line.lineNo);
      appendCell(row, line.installmentNo);
      appendCell(row, line.contractMonthNo);
      appendCell(row, formatDate(line.dueDate));
      appendCell(row, line.commissionItemName);
      appendCell(row, line.recipientName);
      appendCell(row, line.basisCode);
      appendMoneyCell(row, line.basisAmount);
      appendCell(row, label({ RATE: "요율", FIXED: "정액" }, line.calculationType));
      appendNumberCell(row, line.ratePct == null ? "—" : formatNumber(line.ratePct) + "%");
      appendMoneyCell(row, line.expectedAmount);
      appendCell(row, label({ PLANNED: "예정", CONFIRMED: "확정", PAID: "지급", CANCELLED: "취소", ADJUSTED: "조정" }, line.status));
      lineBody.appendChild(row);
    });
    updateTotals(lines.length, total, firstYear);
  }

  function renderLoading() {
    clear(lineBody);
    appendMessage("예상 스케줄을 불러오는 중입니다.", false);
  }

  function renderError(message) {
    clear(lineBody);
    appendMessage(message, true);
    updateTotals(0, 0, 0);
  }

  function appendMessage(message, error) {
    var row = document.createElement("tr");
    var cell = document.createElement("td");
    var content = document.createElement("div");
    cell.colSpan = 12;
    content.className = "fgc-empty";
    content.textContent = message;
    if (error) content.style.color = "#d92d20";
    cell.appendChild(content);
    row.appendChild(cell);
    lineBody.appendChild(row);
  }

  function appendCell(row, content) {
    var cell = document.createElement("td");
    cell.textContent = value(content);
    row.appendChild(cell);
  }

  function appendMoneyCell(row, content) {
    appendNumberCell(row, formatMoney(content));
  }

  function appendNumberCell(row, content) {
    var cell = document.createElement("td");
    cell.className = "fgc-td-num";
    cell.textContent = content;
    row.appendChild(cell);
  }

  function updateTotals(count, total, firstYear) {
    setText("line-count", count);
    setText("line-total", formatMoney(total));
    setText("line-first-year", formatMoney(firstYear));
  }

  function setText(id, content) {
    var element = document.getElementById(id);
    if (element) element.textContent = value(content);
  }

  function label(labels, code) {
    return labels[code] || value(code);
  }

  function join(first, second) {
    return [first, second].filter(function (item) { return item != null && item !== ""; }).join(" · ") || "—";
  }

  function formatMoney(content) {
    return formatNumber(content) + "원";
  }

  function formatNumber(content) {
    return new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 4 }).format(number(content));
  }

  function formatDate(content) {
    return content ? String(content).replace(/-/g, ".") : "—";
  }

  function formatDateTime(content) {
    if (!content) return null;
    var date = new Date(content);
    if (Number.isNaN(date.getTime())) return String(content);
    return new Intl.DateTimeFormat("ko-KR", {
      year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", hour12: false
    }).format(date);
  }

  function number(content) {
    var parsed = Number(content);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function value(content) {
    return content == null || content === "" ? "—" : String(content);
  }

  function clear(element) {
    while (element.firstChild) element.removeChild(element.firstChild);
  }
})();
