(function () {
  "use strict";

  var root = document.querySelector(".publishing-page-arbitrage");
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
    CLEAR: { label: "이상없음", className: "fgc-badge fgc-badge--success" },
    CANDIDATE: { label: "검토대상", className: "fgc-badge fgc-badge--warning" },
    REVIEW_REQUIRED: { label: "자료부족", className: "fgc-badge fgc-badge--closed" }
  };

  function escapeHtml(value) {
    return String(value == null ? "" : value).replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/\"/g, "&quot;").replace(/'/g, "&#039;");
  }
  function number(value) { return new Intl.NumberFormat("ko-KR").format(Number(value || 0)); }
  function money(value) { return number(value) + "원"; }
  function value(value) { return value == null || value === "" ? "—" : String(value); }
  function stageLabel(stage) { return stage === "INSURER_TO_GA" ? "원수사→GA" : stage === "GA_TO_FC" ? "GA→설계사" : value(stage); }
  function statusInfo(status) { return STATUS[status] || { label: value(status), className: "fgc-badge" }; }
  function badge(status) { var item = statusInfo(status); return '<span class="' + item.className + '">● ' + item.label + "</span>"; }
  function refundBadge(row) { return '<span class="fgc-badge">● ' + (row.refundAdditionAppliedYn ? "가산 함" : "해당없음") + "</span>"; }

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
  }

  function rowHtml(row) {
    return '<tr data-arbitrage-id="' + escapeHtml(row.arbitrageCheckId) + '">' +
      '<td><button class="fgc-btn fgc-btn--ghost arb-row-select" type="button">' + escapeHtml(row.contractNo) + '</button></td>' +
      '<td>' + escapeHtml(stageLabel(row.paymentStage)) + '</td><td>' + escapeHtml(value(row.asOfDate)) + '</td><td class="fgc-td-num">' + number(row.contractMonthNo) + '</td>' +
      '<td class="fgc-td-num">' + money(row.cumulativePaidPremium) + '</td><td class="fgc-td-num">' + money(row.paidCommissionAmount) + '</td><td class="fgc-td-num">' + money(row.plannedCommissionAmount) + '</td>' +
      '<td class="fgc-td-num">' + money(row.includedSurrenderValueAmount) + '</td><td>' + escapeHtml(value(row.surrenderValueSourceType)) + '</td>' +
      '<td class="fgc-td-num" style="color:' + (Number(row.netDifferenceAmount) > 0 ? "var(--color-status-error-text)" : "inherit") + '">' + money(row.netDifferenceAmount) + '</td><td>' + refundBadge(row) + '</td><td>' + badge(row.resultStatus) + '</td></tr>';
  }

  function renderDetail(row) {
    state.selectedId = row.arbitrageCheckId;
    state.selectedRow = row;
    var recheckButton = document.getElementById("arb-recheck-button");
    if (recheckButton) recheckButton.disabled = false;
    document.getElementById("detail-badge").innerHTML = badge(row.resultStatus);
    document.getElementById("detail-body").innerHTML =
      '<dl class="fgc-detail-list"><div><dt>계약 · 지급단계</dt><dd>' + escapeHtml(row.contractNo) + " · " + escapeHtml(stageLabel(row.paymentStage)) + '</dd></div>' +
      '<div><dt>기준일 · 계약차월</dt><dd>' + escapeHtml(value(row.asOfDate)) + " · " + number(row.contractMonthNo) + '개월</dd></div>' +
      '<div><dt>환급금 가산 여부</dt><dd>' + (row.refundAdditionAppliedYn ? "가산 함" : "가산 안 함") + '</dd></div><div><dt>환급금 출처</dt><dd>' + escapeHtml(value(row.surrenderValueSourceType)) + '</dd></div>' +
      '<div><dt>판정 근거</dt><dd>' + escapeHtml(value(row.decisionReason)) + '</dd></div></dl>' +
      '<pre class="fgc-mono" style="white-space:pre-wrap;padding:12px;background:var(--color-bg-subtle);border:1px solid var(--color-border-default);border-radius:6px">초과액 = ' + money(row.paidCommissionAmount) + ' (확정 수수료)\n      + ' + money(row.plannedCommissionAmount) + ' (지급예정액)\n      + ' + money(row.includedSurrenderValueAmount) + ' (해약환급금)\n      - ' + money(row.cumulativePaidPremium) + ' (누적 납입보험료)\n      = ' + money(row.netDifferenceAmount) + '</pre><section id="arb-timeline" style="margin-top:16px"><p class="fgc-muted">기준일별 이력을 불러오는 중입니다.</p></section>';
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
    return '<svg viewBox="0 0 320 140" role="img" aria-label="기준일별 초과액 추이" style="width:100%;height:auto"><line x1="24" y1="116" x2="296" y2="116" stroke="currentColor" opacity=".25"/><polyline fill="none" stroke="#e84b3c" stroke-width="3" points="' + points + '"/>' + values.map(function (amount, index) { var point = points.split(" ")[index].split(","); return '<circle cx="' + point[0] + '" cy="' + point[1] + '" r="4" fill="#e84b3c"><title>' + escapeHtml(rows[index].asOfDate) + ' · ' + money(amount) + '</title></circle>'; }).join("") + '</svg>';
  }

  function loadTimeline(row) {
    var requestId = ++state.timelineRequestId;
    var query = new URLSearchParams({ contractNo: row.contractNo, stage: row.paymentStage, page: "1", size: "100" });
    apiClient.request("/api/v1/arbitrage-checks?" + query.toString()).then(function (envelope) {
      if (requestId !== state.timelineRequestId || String(state.selectedId) !== String(row.arbitrageCheckId)) return;
      var rows = ((envelope.data || {}).items || {}).content || [];
      var target = document.getElementById("arb-timeline");
      if (!target) return;
      if (!rows.length) { target.innerHTML = '<p class="fgc-muted">표시할 기준일별 이력이 없습니다.</p>'; return; }
      var ordered = rows.slice().sort(function (left, right) { return String(left.asOfDate).localeCompare(String(right.asOfDate)); });
      target.innerHTML = '<h3 class="fgc-card__title" style="margin:0 0 8px">기준일별 초과액 추이</h3>' + timelineGraph(ordered) +
        '<div style="overflow-x:auto"><table class="fgc-table"><thead><tr><th>기준일</th><th class="fgc-th-num">초과액</th><th>판정</th></tr></thead><tbody>' + ordered.map(function (item) { return '<tr><td>' + escapeHtml(item.asOfDate) + '</td><td class="fgc-td-num">' + money(item.netDifferenceAmount) + '</td><td>' + badge(item.resultStatus) + '</td></tr>'; }).join("") + '</tbody></table></div>';
    }).catch(function () {
      if (requestId !== state.timelineRequestId) return;
      var target = document.getElementById("arb-timeline");
      if (target) target.innerHTML = '<p class="fgc-muted">기준일별 이력을 불러오지 못했습니다.</p>';
    });
  }

  function renderList(items) {
    var rows = (items && items.content) || [];
    state.rows = rows;
    document.getElementById("row-count").textContent = number(items && items.totalElements);
    document.getElementById("list-body").innerHTML = rows.length ? rows.map(rowHtml).join("") : '<tr><td colspan="12"><div class="fgc-empty">조건에 맞는 자료가 없습니다.</div></td></tr>';
    renderPagination(items || {});
    var selected = rows.find(function (row) { return String(row.arbitrageCheckId) === String(state.selectedId); }) || rows[0];
    if (selected) renderDetail(selected); else {
      state.selectedId = null;
      state.selectedRow = null;
      var recheckButton = document.getElementById("arb-recheck-button");
      if (recheckButton) recheckButton.disabled = true;
      document.getElementById("detail-badge").replaceChildren();
      document.getElementById("detail-body").innerHTML = '<p class="fgc-muted" style="font-size:13px;margin:0">왼쪽 목록에서 한 건을 고르세요.</p>';
    }
  }

  function renderPagination(items) {
    var nav = document.getElementById("arb-pagination");
    nav.replaceChildren();
    var page = Number(items.page) || 1, total = Number(items.totalPages) || 0;
    function button(label, target, disabled) {
      var item = document.createElement("button"); item.type = "button"; item.className = "pagination-button"; item.textContent = label; item.disabled = disabled;
      item.addEventListener("click", function () { state.page = target; load(); }); nav.appendChild(item);
    }
    if (total > 1) { button("‹", page - 1, page <= 1); for (var i = 1; i <= total; i += 1) button(String(i), i, i === page); button("›", page + 1, page >= total); }
  }

  function load() {
    if (state.abortController) state.abortController.abort();
    state.abortController = new AbortController();
    var query = params(); replaceLocation(query);
    document.getElementById("list-body").innerHTML = '<tr><td colspan="12"><div class="fgc-empty">차익거래 검증 결과를 불러오는 중입니다.</div></td></tr>';
    apiClient.request("/api/v1/arbitrage-checks?" + query.toString(), { signal: state.abortController.signal }).then(function (envelope) {
      var data = envelope.data || {}; renderSummary(data.summary); renderList(data.items);
    }).catch(function (error) {
      if (error && error.name === "AbortError") return;
      document.getElementById("list-body").innerHTML = '<tr><td colspan="12"><div class="fgc-empty">' + escapeHtml(error.message || "조회에 실패했습니다.") + '</div></td></tr>';
      if (window.FgcUi.toast) window.FgcUi.toast(error.message || "차익거래 검증 결과를 불러오지 못했습니다.", "error");
    });
  }

  [controls.month, controls.status, controls.stage, controls.insurerId].forEach(function (control) { control.addEventListener("change", function () { state.page = 1; state.selectedId = null; load(); }); });
  controls.contractNo.addEventListener("keydown", function (event) { if (event.key === "Enter") { event.preventDefault(); state.page = 1; state.selectedId = null; load(); } });
  document.getElementById("f-reset").addEventListener("click", function () { controls.month.value = root.dataset.initialMonth || ""; controls.status.value = ""; controls.stage.value = ""; controls.insurerId.value = ""; controls.contractNo.value = ""; state.page = 1; state.selectedId = null; load(); });
  document.getElementById("list-body").addEventListener("click", function (event) { var row = event.target.closest("tr[data-arbitrage-id]"); if (!row) return; var id = row.dataset.arbitrageId; var current = state.rows.find(function (item) { return String(item.arbitrageCheckId) === id; }); if (current) renderDetail(current); });
  var recheckButton = document.getElementById("arb-recheck-button");
  if (recheckButton) recheckButton.addEventListener("click", function () {
    if (!state.selectedRow || !window.FgcUi.modal) return;
    document.getElementById("arb-recheck-contract").textContent = state.selectedRow.contractNo + " · " + stageLabel(state.selectedRow.paymentStage);
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
    apiClient.request("/api/v1/contracts/" + encodeURIComponent(state.selectedRow.contractId) + "/arbitrage-check", { method: "POST", body: { asOfDate: date, reason: reason } })
      .then(function () { window.FgcUi.modal.close("arb-recheck"); if (window.FgcUi.toast) window.FgcUi.toast("차익거래 재검증을 완료했습니다.", "success"); state.selectedId = null; load(); })
      .catch(function (error) { if (window.FgcUi.toast) window.FgcUi.toast(error.message || "차익거래 재검증에 실패했습니다.", "error"); })
      .finally(function () { submit.disabled = false; });
  });
  initializeFromLocation();
  loadInsurers().then(load);
})();
