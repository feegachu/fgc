(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var main = document.getElementById("main-content");
  var lineBody = document.getElementById("line-body");
  var versionBody = document.getElementById("version-body");
  var confirmButton = document.getElementById("btn-confirm");
  var regenerateButton = document.getElementById("btn-regenerate");
  var regenerateReason = document.getElementById("regenerate-reason");
  var regenerateSubmit = document.getElementById("btn-regenerate-submit");
  var exportButton = document.getElementById("btn-export");
  var PAYMENT_STAGE_LABELS = { INSURER_TO_GA: "원수사→GA", GA_TO_FC: "GA→설계사" };
  var SCHEDULE_REGIME_LABELS = {
    CURRENT: "현행",
    FOUR_YEAR_2027: "4년 분급(2027)",
    SEVEN_YEAR_2029: "7년 분급(2029)",
    TM_SPECIAL: "TM 특례"
  };
  var SCHEDULE_PURPOSE_LABELS = {
    OPERATIONAL: "운영",
    COMPARISON: "비교",
    SIMULATION: "시뮬레이션"
  };
  var SCHEDULE_STATUS_LABELS = {
    PLANNED: "예정",
    CONFIRMED: "확정",
    MATCHED: "대사일치",
    ADJUSTED: "조정완료",
    HOLD: "보류",
    CANCELLED: "취소",
    RESTARTED: "재개"
  };

  if (!main || !lineBody || !apiClient) return;

  var scheduleHeaderId = main.dataset.scheduleHeaderId || idFromPath();
  var canProcess = confirmButton ? !confirmButton.disabled : false;
  if (!scheduleHeaderId) {
    renderError("스케줄 ID를 확인할 수 없습니다.");
    return;
  }

  load();
  if (confirmButton) confirmButton.addEventListener("click", confirmSchedule);
  if (exportButton) exportButton.addEventListener("click", function () {
    window.location.assign("/api/v1/schedules/" + encodeURIComponent(scheduleHeaderId) + "/export.csv");
  });
  if (regenerateButton) regenerateButton.addEventListener("click", regenerateSchedule);
  if (regenerateReason) regenerateReason.addEventListener("input", updateRegenerateSubmit);
  if (regenerateSubmit) regenerateSubmit.addEventListener("click", submitRegeneration);

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
        renderLines(Array.isArray(detail.lines) ? detail.lines : []);
        loadVersions();
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
    setText("hdr-stage", responseLabel(header.paymentStageLabel, PAYMENT_STAGE_LABELS, header.paymentStage));
    setText("hdr-regime", responseLabel(header.scheduleRegimeLabel, SCHEDULE_REGIME_LABELS, header.scheduleRegime));
    setText("hdr-purpose", responseLabel(header.schedulePurposeLabel, SCHEDULE_PURPOSE_LABELS, header.schedulePurpose));
    setText("hdr-version", header.scheduleVersionNo == null ? "—" : "v" + header.scheduleVersionNo);
    setText("hdr-status", responseLabel(header.statusLabel, SCHEDULE_STATUS_LABELS, header.status));
    setText("hdr-active", header.activeYn === true ? "사용중" : "미사용");
    setText("hdr-policy", header.policyVersionLabel);
    setText("hdr-reason", join(header.generationReason, formatDateTime(header.generatedAt)));

    var stage = document.getElementById("stage");
    if (stage && header.paymentStage) {
      stage.value = header.paymentStage;
      stage.disabled = true;
    }
    if (confirmButton) {
      confirmButton.disabled = !canProcess || header.status !== "PLANNED" || header.activeYn !== true;
      confirmButton.title = header.status === "CONFIRMED" ? "이미 확정된 스케줄입니다." : "";
    }
    if (regenerateButton) {
      regenerateButton.disabled = !canProcess || header.activeYn !== true || header.status === "CANCELLED";
    }
  }

  function confirmSchedule() {
    if (!confirmButton || confirmButton.disabled) return;

    confirmButton.disabled = true;
    apiClient.request("/api/v1/schedules/" + encodeURIComponent(scheduleHeaderId) + "/confirm", {
      method: "POST"
    }).then(function (envelope) {
      var detail = envelope && envelope.data;
      if (!detail || !detail.header) throw new Error("Invalid schedule confirmation response");
      renderHeader(detail.header);
      renderLines(Array.isArray(detail.lines) ? detail.lines : []);
      loadVersions();
      toast("스케줄을 확정했습니다. 이제 금액을 고칠 수 없습니다. 바꾸려면 새 버전을 만드세요.", "success", 4500);
    }).catch(function (error) {
      console.error(error);
      toast(error && error.message ? error.message : "예상 스케줄을 확정하지 못했습니다.", "error", 5000);
      confirmButton.disabled = false;
    });
  }

  function regenerateSchedule() {
    if (!regenerateButton || regenerateButton.disabled) return;
    if (regenerateReason) regenerateReason.value = "";
    updateRegenerateSubmit();
    if (window.FgcUi && window.FgcUi.modal) window.FgcUi.modal.open("schedule-regenerate");
  }

  function updateRegenerateSubmit() {
    if (!regenerateSubmit) return;
    var reason = regenerateReason ? regenerateReason.value.trim() : "";
    regenerateSubmit.disabled = !reason || reason.length > 40;
  }

  function submitRegeneration() {
    if (!regenerateSubmit || regenerateSubmit.disabled) return;
    var reason = regenerateReason ? regenerateReason.value.trim() : "";
    if (!reason) {
      toast("재생성 사유를 입력하세요.", "error", 4000);
      return;
    }
    if (reason.length > 40) {
      toast("재생성 사유는 40자 이하여야 합니다.", "error", 4000);
      return;
    }

    regenerateButton.disabled = true;
    regenerateSubmit.disabled = true;
    apiClient.request("/api/v1/schedules/" + encodeURIComponent(scheduleHeaderId) + "/regenerate", {
      method: "POST",
      body: { reason: reason }
    }).then(function (envelope) {
      var result = envelope && envelope.data;
      if (!result || !result.scheduleHeaderId) throw new Error("Invalid schedule regeneration response");
      if (window.FgcUi && window.FgcUi.modal) window.FgcUi.modal.close("schedule-regenerate");
      toast("새 스케줄 버전을 만들었습니다. 새 버전으로 이동합니다.", "success", 3500);
      window.setTimeout(function () {
        window.location.assign("/schedules/" + encodeURIComponent(result.scheduleHeaderId));
      }, 700);
    }).catch(function (error) {
      console.error(error);
      toast(error && error.message ? error.message : "새 스케줄 버전을 만들지 못했습니다.", "error", 5000);
      regenerateButton.disabled = false;
      updateRegenerateSubmit();
    });
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
      appendNumberCell(row, line.ratePct == null ? "—" : formatRate(line.ratePct) + "%");
      appendMoneyCell(row, line.expectedAmount);
      appendCell(row, label({ PLANNED: "예정", CONFIRMED: "확정", MATCHED: "대사일치", ADJUSTED: "조정" }, line.lineStatus));
      lineBody.appendChild(row);
    });
    updateTotals(lines.length, total, firstYear);
  }

  function loadVersions() {
    if (!versionBody) return;
    renderVersionMessage("버전 이력을 불러오는 중입니다.", false);
    apiClient.request("/api/v1/schedules/" + encodeURIComponent(scheduleHeaderId) + "/versions")
      .then(function (envelope) {
        renderVersions(envelope && Array.isArray(envelope.data) ? envelope.data : []);
      })
      .catch(function (error) {
        console.error(error);
        renderVersionMessage(error && error.message
          ? error.message
          : "버전 이력을 불러오지 못했습니다.", true);
      });
  }

  function renderVersions(versions) {
    clear(versionBody);
    if (versions.length === 0) {
      renderVersionMessage("같은 계약의 스케줄 버전이 없습니다.", false);
      return;
    }
    versions.forEach(function (version) {
      var row = document.createElement("tr");
      if (String(version.scheduleHeaderId) === String(scheduleHeaderId)) row.style.fontWeight = "700";

      var versionCell = document.createElement("td");
      var link = document.createElement("a");
      link.href = "/schedules/" + encodeURIComponent(version.scheduleHeaderId);
      link.textContent = version.scheduleVersionNo == null ? "—" : "v" + version.scheduleVersionNo;
      versionCell.appendChild(link);
      row.appendChild(versionCell);

      appendCell(row, responseLabel(version.statusLabel, SCHEDULE_STATUS_LABELS, version.status));
      appendCell(row, version.activeYn === true ? "사용중" : "미사용");
      appendCell(row, version.policyVersionLabel);
      appendCell(row, version.generationReason);
      appendCell(row, formatDateTime(version.generatedAt));
      appendNumberCell(row, formatNumber(version.lineCount));
      appendMoneyCell(row, version.expectedTotal);
      versionBody.appendChild(row);
    });
  }

  function renderVersionMessage(message, error) {
    if (!versionBody) return;
    clear(versionBody);
    var row = document.createElement("tr");
    var cell = document.createElement("td");
    var content = document.createElement("div");
    cell.colSpan = 8;
    content.className = "fgc-empty";
    content.textContent = message;
    if (error) content.style.color = "#d92d20";
    cell.appendChild(content);
    row.appendChild(cell);
    versionBody.appendChild(row);
  }

  function renderLoading() {
    clear(lineBody);
    appendMessage("예상 스케줄을 불러오는 중입니다.", false);
  }

  function renderError(message) {
    clear(lineBody);
    var content = appendMessage(message, true);
    var retryButton = document.createElement("button");
    retryButton.type = "button";
    retryButton.className = "fgc-btn fgc-btn--ghost";
    retryButton.style.marginTop = "12px";
    retryButton.textContent = "다시 시도";
    retryButton.addEventListener("click", load);
    content.appendChild(document.createElement("br"));
    content.appendChild(retryButton);
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
    return content;
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

  function responseLabel(serverLabel, labels, code) {
    return typeof serverLabel === "string" && serverLabel.trim()
      ? serverLabel
      : label(labels, code);
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

  function formatRate(content) {
    return new Intl.NumberFormat("ko-KR", {
      minimumFractionDigits: 4,
      maximumFractionDigits: 4
    }).format(number(content));
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

  function toast(message, tone, duration) {
    if (window.FgcUi && typeof window.FgcUi.toast === "function") {
      window.FgcUi.toast(message, tone, duration);
    }
  }
})();
