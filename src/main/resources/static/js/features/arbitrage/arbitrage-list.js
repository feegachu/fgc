(function () {
  "use strict";

  var root = document.querySelector(".arb-page");
  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  if (!root || !apiClient) return;

  var controls = {
    month: document.getElementById("f-month"),
    status: document.getElementById("f-status"),
    stage: document.getElementById("f-stage"),
    insurerId: document.getElementById("f-insurer"),
    contractNo: document.getElementById("f-contract")
  };
  var state = { page: 1, abortController: null, selectedId: null, selectedRow: null, rows: [], timelineRequestId: 0 };
  var STATUS = {
    CLEAR: "status-badge status-badge-success",
    CANDIDATE: "status-badge status-badge-warning",
    REVIEW_REQUIRED: "status-badge status-badge-review"
  };

  function escapeHtml(value) {
    return String(value == null ? "" : value).replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/\"/g, "&quot;").replace(/'/g, "&#039;");
  }
  function number(value) { return new Intl.NumberFormat("ko-KR").format(Number(value || 0)); }
  function money(value) { return number(value) + "원"; }
  function value(value) { return value == null || value === "" ? "—" : String(value); }
  function stageLabel(row) { return value(row.paymentStageLabel); }
  function surrenderValueSourceLabel(row) { return value(row.surrenderValueSourceTypeLabel); }
  function badge(row) {
    return '<span class="' + (STATUS[row.resultStatus] || "status-badge status-badge-neutral") + '">' +
      escapeHtml(value(row.resultStatusLabel)) + "</span>";
  }

  function refundBadge(row) {
    var tone = row.refundAdditionAppliedYn ? "status-badge-info" : "status-badge-neutral";
    return '<span class="status-badge ' + tone + '">' +
      (row.refundAdditionAppliedYn ? "가산 함" : "가산 안 함") + "</span>";
  }

  function initializeFromLocation() {
    var params = new URLSearchParams(window.location.search);
    ["month", "status", "stage", "insurerId", "contractNo"].forEach(function (key) {
      if (params.has(key)) controls[key].value = params.get(key);
    });
    if (!controls.month.value) controls.month.value = root.dataset.initialMonth || "";
    state.page = Math.max(1, Number(params.get("page")) || 1);
  }

  function params() {
    var result = new URLSearchParams();
    Object.keys(controls).forEach(function (key) {
      var control = controls[key];
      var selected = control && control.value.trim();
      if (selected) result.set(key, selected);
    });
    result.set("page", String(state.page));
    result.set("size", "20");
    return result;
  }

  function replaceLocation(query) { window.history.replaceState(null, "", window.location.pathname + "?" + query.toString()); }

  function loadInsurers() {
    controls.insurerId.disabled = true;
    return apiClient.request("/api/v1/base/insurers?page=1&size=100").then(function (envelope) {
      var selected = controls.insurerId.value;
      controls.insurerId.replaceChildren(new Option("전체", ""));
      ((envelope.data && envelope.data.content) || []).forEach(function (row) {
        var option = new Option(row.insurerCode + " · " + row.insurerName, row.insurerId);
        option.disabled = row.activeYn === false;
        controls.insurerId.add(option);
      });
      controls.insurerId.value = selected;
      controls.insurerId.disabled = false;
    }).catch(function (error) {
      controls.insurerId.replaceChildren(new Option("보험회사 조회 실패", ""));
      if (window.FgcUi.toast) window.FgcUi.toast(error.message || "보험회사 목록을 불러오지 못했습니다.", "error");
    });
  }

  function renderSummary(summary) {
    summary = summary || {};
    document.getElementById("summary-clear").textContent = number(summary.clearCount) + "건";
    document.getElementById("summary-candidate").textContent = number(summary.candidateCount) + "건";
    document.getElementById("summary-review").textContent = number(summary.reviewRequiredCount) + "건";
    document.querySelectorAll("[data-arb-summary-status]").forEach(function (card) {
      var selected = card.dataset.arbSummaryStatus === controls.status.value;
      card.setAttribute("aria-pressed", String(selected));
    });
  }

  function rowHtml(row) {
    var contractNo = value(row.contractNo);
    var differenceClass = Number(row.netDifferenceAmount) > 0 ? " arb-amount-error" : "";
    return '<tr class="arb-result-row" data-arbitrage-id="' + escapeHtml(row.arbitrageCheckId) +
      '" tabindex="0" aria-selected="false" aria-label="계약 ' + escapeHtml(contractNo) + ' 상세 보기">' +
      '<td class="tabular-nums" title="' + escapeHtml(contractNo) + '">' + escapeHtml(contractNo) + '</td>' +
      '<td>' + escapeHtml(stageLabel(row)) + '</td>' +
      '<td class="tabular-nums">' + escapeHtml(value(row.asOfDate)) + '</td>' +
      '<td class="is-number tabular-nums">' + number(row.contractMonthNo) + '</td>' +
      '<td class="is-number tabular-nums">' + money(row.cumulativePaidPremium) + '</td>' +
      '<td class="is-number tabular-nums">' + money(row.paidCommissionAmount) + '</td>' +
      '<td class="is-number tabular-nums">' + money(row.plannedCommissionAmount) + '</td>' +
      '<td class="is-number tabular-nums">' + money(row.includedSurrenderValueAmount) + '</td>' +
      '<td>' + escapeHtml(surrenderValueSourceLabel(row)) + '</td>' +
      '<td class="is-number tabular-nums' + differenceClass + '">' + money(row.netDifferenceAmount) + '</td>' +
      '<td>' + refundBadge(row) + '</td><td>' + badge(row) + '</td></tr>';
  }

  function renderDetail(row) {
    state.selectedId = row.arbitrageCheckId;
    state.selectedRow = row;
    document.querySelectorAll(".arb-result-row").forEach(function (candidate) {
      var selected = String(candidate.dataset.arbitrageId) === String(row.arbitrageCheckId);
      candidate.classList.toggle("is-selected", selected);
      candidate.setAttribute("aria-selected", String(selected));
    });
    var recheckButton = document.getElementById("arb-recheck-button");
    if (recheckButton) recheckButton.disabled = false;
    document.getElementById("detail-badge").innerHTML = badge(row);
    document.getElementById("detail-body").innerHTML =
      '<dl class="arb-detail-list"><div><dt>계약 · 지급단계</dt><dd>' + escapeHtml(row.contractNo) + " · " + escapeHtml(stageLabel(row)) + '</dd></div>' +
      '<div><dt>기준일 · 계약차월</dt><dd>' + escapeHtml(value(row.asOfDate)) + " · " + number(row.contractMonthNo) + '개월</dd></div>' +
      '<div><dt>환급금 가산 여부</dt><dd>' + (row.refundAdditionAppliedYn ? "가산 함" : "가산 안 함") + '</dd></div><div><dt>환급금 출처</dt><dd>' + escapeHtml(surrenderValueSourceLabel(row)) + '</dd></div>' +
      '<div><dt>판정 근거</dt><dd>' + escapeHtml(value(row.decisionReason)) + '</dd></div></dl>' +
      '<pre class="arb-calculation tabular-nums">초과액 = ' + money(row.paidCommissionAmount) + ' (확정 수수료)\n      + ' + money(row.plannedCommissionAmount) + ' (지급예정액)\n      + ' + money(row.includedSurrenderValueAmount) + ' (해약환급금)\n      - ' + money(row.cumulativePaidPremium) + ' (누적 납입보험료)\n      = ' + money(row.netDifferenceAmount) + '</pre>' +
      '<section id="arb-timeline" class="arb-timeline-panel" aria-label="기준일별 초과액 추이">' +
      '<p class="empty-state arb-timeline-state">기준일별 이력을 불러오는 중입니다.</p></section>';
    loadTimeline(row);
  }

  function timelineGraph(rows) {
    if (!rows.length) return "";
    var values = rows.map(function (row) { return Number(row.netDifferenceAmount) || 0; });
    var maximum = Math.max.apply(null, values.concat([0]));
    var minimum = Math.min.apply(null, values.concat([0]));
    var range = maximum - minimum || 1;
    var points = values.map(function (amount, index) {
      var x = 24 + (rows.length === 1 ? 136 : index * 272 / (rows.length - 1));
      var y = 116 - (amount - minimum) * 92 / range;
      return x.toFixed(1) + "," + y.toFixed(1);
    }).join(" ");
    return '<svg class="arb-timeline-chart" viewBox="0 0 320 140" role="img" aria-label="기준일별 초과액 추이">' +
      '<line class="arb-timeline-axis" x1="24" y1="116" x2="296" y2="116"/>' +
      '<polyline class="arb-timeline-line" points="' + points + '"/>' + values.map(function (amount, index) {
        var point = points.split(" ")[index].split(",");
        return '<circle class="arb-timeline-point" cx="' + point[0] + '" cy="' + point[1] + '" r="4"><title>' +
          escapeHtml(rows[index].asOfDate) + ' · ' + money(amount) + '</title></circle>';
      }).join("") + '</svg>';
  }

  function loadTimeline(row) {
    var requestId = ++state.timelineRequestId;
    var query = new URLSearchParams({ contractNo: row.contractNo, stage: row.paymentStage, page: "1", size: "100" });
    apiClient.request("/api/v1/arbitrage-checks?" + query.toString()).then(function (envelope) {
      if (requestId !== state.timelineRequestId || String(state.selectedId) !== String(row.arbitrageCheckId)) return;
      var rows = ((envelope.data || {}).items || {}).content || [];
      var target = document.getElementById("arb-timeline");
      if (!target) return;
      if (!rows.length) {
        target.innerHTML = '<p class="empty-state arb-timeline-state">표시할 기준일별 이력이 없습니다.</p>';
        return;
      }
      var ordered = rows.slice().sort(function (left, right) { return String(left.asOfDate).localeCompare(String(right.asOfDate)); });
      target.innerHTML = '<h3 class="arb-timeline-title">기준일별 초과액 추이</h3>' + timelineGraph(ordered) +
        '<div class="arb-timeline-table"><table class="data-table arb-timeline-data-table">' +
        '<thead><tr><th scope="col">기준일</th><th scope="col" class="is-number">차월</th>' +
        '<th scope="col" class="is-number">누적 보험료</th><th scope="col" class="is-number">지급수수료</th>' +
        '<th scope="col" class="is-number">초과액</th></tr></thead><tbody>' + ordered.map(function (item) {
          var differenceClass = Number(item.netDifferenceAmount) > 0 ? " arb-amount-error" : "";
          return '<tr><td class="tabular-nums">' + escapeHtml(item.asOfDate) + '</td>' +
            '<td class="is-number tabular-nums">' + number(item.contractMonthNo) + '</td>' +
            '<td class="is-number tabular-nums">' + money(item.cumulativePaidPremium) + '</td>' +
            '<td class="is-number tabular-nums">' + money(item.paidCommissionAmount) + '</td>' +
            '<td class="is-number tabular-nums' + differenceClass + '">' + money(item.netDifferenceAmount) + '</td></tr>';
        }).join("") + '</tbody></table></div>';
    }).catch(function () {
      if (requestId !== state.timelineRequestId) return;
      var target = document.getElementById("arb-timeline");
      if (target) target.innerHTML = '<p class="empty-state arb-timeline-state">기준일별 이력을 불러오지 못했습니다.</p>';
    });
  }

  function renderList(items) {
    var rows = (items && items.content) || [];
    state.rows = rows;
    document.getElementById("row-count").textContent = number(items && items.totalElements);
    document.getElementById("list-body").innerHTML = rows.length ? rows.map(rowHtml).join("") :
      '<tr class="arb-state-row"><td colspan="12"><div class="empty-state">조건에 맞는 자료가 없습니다.</div></td></tr>';
    renderPagination(items || {});
    var selected = rows.find(function (row) { return String(row.arbitrageCheckId) === String(state.selectedId); }) || rows[0];
    if (selected) renderDetail(selected); else {
      state.selectedId = null;
      state.selectedRow = null;
      var recheckButton = document.getElementById("arb-recheck-button");
      if (recheckButton) recheckButton.disabled = true;
      document.getElementById("detail-badge").replaceChildren();
      document.getElementById("detail-body").innerHTML = '<p class="arb-detail-placeholder">왼쪽 목록에서 한 건을 고르세요.</p>';
    }
  }

  function renderPagination(items) {
    var nav = document.getElementById("arb-pagination");
    nav.replaceChildren();
    var page = Number(items.page) || 1, total = Number(items.totalPages) || 0;
    function button(label, target, disabled, current) {
      var item = document.createElement("button");
      item.type = "button";
      item.className = "pagination-button" + (current ? " is-active" : "");
      item.textContent = label;
      item.disabled = disabled;
      if (current) item.setAttribute("aria-current", "page");
      item.addEventListener("click", function () { state.page = target; load(); }); nav.appendChild(item);
    }
    if (total > 1) {
      button("‹", page - 1, page <= 1, false);
      for (var i = 1; i <= total; i += 1) button(String(i), i, i === page, i === page);
      button("›", page + 1, page >= total, false);
    }
  }

  function load() {
    if (state.abortController) state.abortController.abort();
    state.abortController = new AbortController();
    var query = params(); replaceLocation(query);
    document.getElementById("list-body").innerHTML = '<tr class="arb-state-row"><td colspan="12"><div class="empty-state">차익거래 검증 결과를 불러오는 중입니다.</div></td></tr>';
    apiClient.request("/api/v1/arbitrage-checks?" + query.toString(), { signal: state.abortController.signal }).then(function (envelope) {
      var data = envelope.data || {}; renderSummary(data.summary); renderList(data.items);
    }).catch(function (error) {
      if (error && error.name === "AbortError") return;
      document.getElementById("list-body").innerHTML = '<tr class="arb-state-row"><td colspan="12"><div class="empty-state">' + escapeHtml(error.message || "조회에 실패했습니다.") + '</div></td></tr>';
      if (window.FgcUi.toast) window.FgcUi.toast(error.message || "차익거래 검증 결과를 불러오지 못했습니다.", "error");
    });
  }

  [controls.month, controls.status, controls.stage, controls.insurerId].forEach(function (control) { control.addEventListener("change", function () { state.page = 1; state.selectedId = null; load(); }); });
  controls.contractNo.addEventListener("keydown", function (event) { if (event.key === "Enter") { event.preventDefault(); state.page = 1; state.selectedId = null; load(); } });
  document.getElementById("f-reset").addEventListener("click", function () { controls.month.value = root.dataset.initialMonth || ""; controls.status.value = ""; controls.stage.value = "GA_TO_FC"; controls.insurerId.value = ""; controls.contractNo.value = ""; state.page = 1; state.selectedId = null; load(); });
  document.getElementById("summary-cards").addEventListener("click", function (event) {
    var card = event.target.closest("[data-arb-summary-status]");
    if (!card) return;
    controls.status.value = card.dataset.arbSummaryStatus;
    state.page = 1;
    state.selectedId = null;
    load();
  });
  function selectResultRow(row) {
    if (!row) return;
    var id = row.dataset.arbitrageId;
    var current = state.rows.find(function (item) { return String(item.arbitrageCheckId) === id; });
    if (current) renderDetail(current);
  }

  document.getElementById("list-body").addEventListener("click", function (event) {
    selectResultRow(event.target.closest("tr[data-arbitrage-id]"));
  });
  document.getElementById("list-body").addEventListener("keydown", function (event) {
    if (event.key !== "Enter" && event.key !== " ") return;
    var row = event.target.closest("tr[data-arbitrage-id]");
    if (!row) return;
    event.preventDefault();
    selectResultRow(row);
  });
  var recheckButton = document.getElementById("arb-recheck-button");
  if (recheckButton) recheckButton.addEventListener("click", function () {
    if (!state.selectedRow || !window.FgcUi.modal) return;
    document.getElementById("arb-recheck-contract").textContent = state.selectedRow.contractNo + " · " + stageLabel(state.selectedRow);
    document.getElementById("arb-recheck-date").value = state.selectedRow.asOfDate || "";
    document.getElementById("arb-recheck-reason").value = "";
    window.FgcUi.modal.open("arb-recheck");
  });
  document.getElementById("arb-recheck-submit").addEventListener("click", function () {
    if (!state.selectedRow) return;
    var date = document.getElementById("arb-recheck-date").value;
    var reason = document.getElementById("arb-recheck-reason").value.trim();
    if (!date || !reason) { if (window.FgcUi.toast) window.FgcUi.toast("검증 기준일과 재검증 사유를 입력하세요.", "error"); return; }
    var submit = document.getElementById("arb-recheck-submit"); submit.disabled = true;
    var recheck = document.getElementById("arb-recheck-button");
    if (recheck) { recheck.disabled = true; recheck.classList.add("is-loading"); }
    apiClient.request("/api/v1/contracts/" + encodeURIComponent(state.selectedRow.contractId) + "/arbitrage-check", { method: "POST", body: { asOfDate: date, reason: reason } })
      .then(function () { window.FgcUi.modal.close("arb-recheck"); if (window.FgcUi.toast) window.FgcUi.toast("차익거래 재검증을 완료했습니다.", "success"); state.selectedId = null; load(); })
      .catch(function (error) { if (window.FgcUi.toast) window.FgcUi.toast(error.message || "차익거래 재검증에 실패했습니다.", "error"); })
      .finally(function () { submit.disabled = false; if (recheck) { recheck.disabled = false; recheck.classList.remove("is-loading"); } });
  });
  initializeFromLocation();
  loadInsurers().then(load);
})();
