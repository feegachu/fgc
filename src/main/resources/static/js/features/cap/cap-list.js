(function () {
  "use strict";

  var root = document.querySelector(".cap-page");
  if (!root) return;

  var form = document.getElementById("cap-search-form");
  var controls = {
    month: document.getElementById("cap-month"),
    stage: document.getElementById("cap-stage"),
    status: document.getElementById("cap-status"),
    insurerId: document.getElementById("cap-insurer"),
    contractNo: document.getElementById("cap-contract-no"),
    size: document.getElementById("cap-page-size")
  };
  var state = {
    page: 1,
    abortController: null,
    detailAbortController: null,
    detailRequestId: 0
  };

  var STATUS_CLASS = {
    NORMAL: "status-badge-success",
    WARNING: "status-badge-warning",
    VIOLATION: "status-badge-error",
    REVIEW_REQUIRED: "status-badge-review"
  };
  var STATUS_PROGRESS_CLASS = {
    NORMAL: "",
    WARNING: "is-warning",
    VIOLATION: "is-violation",
    REVIEW_REQUIRED: "is-review"
  };
  var CLASSIFICATION_LABEL = {
    INCLUDED: "산입",
    EXCLUDED: "제외",
    REVIEW_REQUIRED: "검토필요"
  };

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  function number(value) {
    return new Intl.NumberFormat("ko-KR").format(Number(value || 0));
  }

  function won(value) {
    return number(value) + "원";
  }

  function refundAddition(value) {
    return Number(value) === 0 ? "해당없음" : won(value);
  }

  function remainingAmount(value) {
    var parsed = Number(value);
    if (parsed < 0) {
      return '<span class="cap-negative-amount">(' + number(Math.abs(parsed)) + "원)</span>";
    }
    return won(value);
  }

  function percent(value) {
    return value == null || value === "" ? "—" : String(value) + "%";
  }

  function visualWidth(value) {
    var parsed = Number(value);
    if (!Number.isFinite(parsed) || parsed <= 0) return "0%";
    return Math.min(parsed, 100) + "%";
  }

  function queryFromLocation() {
    var params = new URLSearchParams(window.location.search);
    ["month", "stage", "status", "contractNo"].forEach(function (key) {
      if (!params.has(key) || !controls[key]) return;
      controls[key].value = params.get(key);
    });
    if (!controls.month.value) controls.month.value = root.dataset.initialMonth || "";
    if (params.has("page")) state.page = Math.max(1, Number(params.get("page")) || 1);
    if (params.has("size")) controls.size.value = params.get("size");
  }

  function currentParams() {
    var params = new URLSearchParams();
    ["month", "stage", "status", "insurerId", "contractNo"].forEach(function (key) {
      if (!controls[key] || controls[key].disabled) return;
      var value = controls[key].value.trim();
      if (value) params.set(key, value);
    });
    params.set("page", String(state.page));
    params.set("size", controls.size.value || "20");
    return params;
  }

  function replaceLocation(params) {
    var url = window.location.pathname + "?" + params.toString();
    window.history.replaceState(null, "", url);
  }

  function setLoading() {
    document.getElementById("cap-kpi-grid").setAttribute("aria-busy", "true");
    document.getElementById("cap-list-message").className = "cap-inline-message";
    document.getElementById("cap-list-message").textContent = "판정 목록을 불러오는 중입니다.";
    document.getElementById("cap-list-message").hidden = false;
    document.getElementById("cap-contract-table-wrap").hidden = true;
    document.getElementById("cap-pagination").replaceChildren();
  }

  function setError(error) {
    var message = error && error.message ? error.message : "한도 검증 현황을 불러오지 못했습니다.";
    var element = document.getElementById("cap-list-message");
    element.className = "cap-inline-message is-error";
    element.textContent = message;
    element.hidden = false;
    document.getElementById("cap-contract-table-wrap").hidden = true;
    document.getElementById("cap-total-count").textContent = "0";
    document.querySelectorAll("[data-kpi-value]").forEach(function (value) {
      value.textContent = "—";
    });
    document.getElementById("cap-kpi-grid").setAttribute("aria-busy", "false");
    document.getElementById("cap-pagination").replaceChildren();
  }

  function renderKpis(summary) {
    summary = summary || {};
    var values = {
      NORMAL: summary.normal,
      WARNING: summary.warning,
      VIOLATION: summary.violation,
      REVIEW_REQUIRED: summary.reviewRequired
    };
    document.querySelectorAll("[data-kpi-value]").forEach(function (element) {
      element.textContent = number(values[element.dataset.kpiValue]);
    });
    document.querySelectorAll("[data-cap-status]").forEach(function (button) {
      button.setAttribute("aria-pressed", String(button.dataset.capStatus === controls.status.value));
    });
    document.getElementById("cap-kpi-grid").setAttribute("aria-busy", "false");
  }

  function statusBadge(item) {
    return '<span class="status-badge ' + (STATUS_CLASS[item.resultStatus] || "status-badge-neutral") + '">' + escapeHtml(item.resultStatusLabel) + '</span>';
  }

  function contractRow(item) {
    var isAgent = item.paymentStage === "GA_TO_FC";
    var deduction = isAgent ? '<span class="cap-not-applicable">적용하지 않음</span>' : won(item.complianceDeductionAmount);
    return "<tr>" +
      '<td><a class="cap-contract-link" href="/contracts/' + encodeURIComponent(item.contractId) + '?tab=cap">' + escapeHtml(item.contractNo) + '</a></td>' +
      '<td><span class="cap-stage-label"><span class="cap-stage-dot' + (isAgent ? " is-agent" : "") + '"></span>' + escapeHtml(item.paymentStageLabel) + '</span></td>' +
      '<td class="tabular-nums">' + escapeHtml(item.asOfDate) + '</td>' +
      '<td class="text-right tabular-nums">' + won(item.basePremiumAmount) + '</td>' +
      '<td class="text-right tabular-nums">' + refundAddition(item.refund12mAmount) + '</td>' +
      '<td class="text-right tabular-nums">' + deduction + '</td>' +
      '<td class="text-right tabular-nums">' + won(item.limitAmount) + '</td>' +
      '<td class="text-right tabular-nums"><button class="cap-basis-button" type="button" data-cap-detail-id="' + escapeHtml(item.capCheckId) + '">' + won(item.includedAmount) + '</button></td>' +
      '<td class="text-right tabular-nums">' + remainingAmount(item.remainingAmount) + '</td>' +
      '<td><div class="cap-usage-cell"><span class="cap-usage-track"><span class="cap-usage-bar ' + (STATUS_PROGRESS_CLASS[item.resultStatus] || "") + '" style="--cap-progress:' + visualWidth(item.usagePct) + '"></span></span><span class="cap-usage-value">' + escapeHtml(percent(item.usagePct)) + '</span></div></td>' +
      '<td class="text-center">' + statusBadge(item) + '</td>' +
      '<td class="tabular-nums">ID ' + escapeHtml(item.capRuleSetId) + '</td></tr>';
  }

  function renderContracts(data) {
    var message = document.getElementById("cap-list-message");
    var tableWrap = document.getElementById("cap-contract-table-wrap");
    document.getElementById("cap-total-count").textContent = number(data.totalElements);
    if (!data.content || !data.content.length) {
      message.className = "cap-inline-message";
      message.textContent = "조건에 맞는 계약 판정이 없습니다.";
      message.hidden = false;
      tableWrap.hidden = true;
    } else {
      document.getElementById("cap-contract-body").innerHTML = data.content.map(contractRow).join("");
      message.hidden = true;
      tableWrap.hidden = false;
    }
    renderPagination(data.page, data.totalPages);
  }

  function pageButton(label, page, options) {
    var button = document.createElement("button");
    button.type = "button";
    button.className = "pagination-button" + (options.active ? " is-active" : "") + (options.disabled ? " is-disabled" : "");
    button.textContent = label;
    button.disabled = options.disabled;
    if (options.active) button.setAttribute("aria-current", "page");
    if (!options.disabled && !options.active) {
      button.addEventListener("click", function () {
        state.page = page;
        load();
      });
    }
    return button;
  }

  function renderPagination(page, totalPages) {
    var pagination = document.getElementById("cap-pagination");
    pagination.replaceChildren();
    if (!totalPages) return;
    pagination.appendChild(pageButton("‹", page - 1, { disabled: page <= 1, active: false }));
    var start = Math.max(1, page - 2);
    var end = Math.min(totalPages, start + 4);
    start = Math.max(1, end - 4);
    for (var current = start; current <= end; current += 1) {
      pagination.appendChild(pageButton(String(current), current, { disabled: false, active: current === page }));
    }
    pagination.appendChild(pageButton("›", page + 1, { disabled: page >= totalPages, active: false }));
  }

  function render(data) {
    renderKpis(data.summary);
    renderContracts(data);
  }

  function load() {
    if (state.abortController) state.abortController.abort();
    state.abortController = new AbortController();
    var params = currentParams();
    replaceLocation(params);
    setLoading();
    window.FgcUi.apiClient.request("/api/v1/cap-checks?" + params.toString(), { signal: state.abortController.signal })
      .then(function (envelope) { render(envelope.data); })
      .catch(function (error) {
        if (error && error.name === "AbortError") return;
        setError(error);
      });
  }

  function detailPair(label, value) {
    return "<tr><th scope=\"row\">" + escapeHtml(label) + "</th><td class=\"text-right tabular-nums\">" + escapeHtml(value) + "</td></tr>";
  }

  function detailPairHtml(label, valueHtml) {
    return "<tr><th scope=\"row\">" + escapeHtml(label) + "</th><td class=\"text-right tabular-nums\">" + valueHtml + "</td></tr>";
  }

  function renderDetail(data) {
    var item = data.capCheck;
    var snapshot = data.calculationSnapshot || {};
    document.getElementById("sum-contract").textContent = item.contractNo || "—";
    document.getElementById("sum-stage").textContent = item.paymentStageLabel || "—";
    document.getElementById("sum-asof").textContent = item.asOfDate || "—";
    document.getElementById("sum-kind").textContent = item.checkKind || "저장 판정";
    document.getElementById("sum-ruleset").textContent = "ID " + item.capRuleSetId;
    document.getElementById("sum-id").textContent = item.capCheckId;
    document.getElementById("sum-badge").innerHTML = statusBadge(item);

    var insurerStage = item.paymentStage === "INSURER_TO_GA";
    document.getElementById("input-body").innerHTML =
      detailPair("월납환산 초회보험료", won(item.basePremiumAmount)) +
      detailPair("12차월 환급금 가산", refundAddition(item.refund12mAmount)) +
      detailPair("준법경영비 공제", insurerStage ? won(item.complianceDeductionAmount) : "적용하지 않음") +
      detailPair("보험료 배수", snapshot.premiumMultiplier == null ? "—" : snapshot.premiumMultiplier);

    document.getElementById("formula").textContent = insurerStage
      ? "기준 보험료 × 배수 + 환급금 가산 − 준법경영비 공제"
      : "기준 보험료 × 배수 + 환급금 가산";
    document.getElementById("formula-note").textContent = insurerStage
      ? "원수사 → GA 단계의 저장된 공제 금액을 표시합니다."
      : "GA → 설계사 단계에는 준법경영비 공제를 적용하지 않습니다.";

    document.getElementById("final-bar").style.width = visualWidth(item.usagePct);
    document.getElementById("final-limit-label").textContent = "한도 " + won(item.limitAmount);
    document.getElementById("final-body").innerHTML =
      detailPair("한도", won(item.limitAmount)) + detailPair("산입금액", won(item.includedAmount)) +
      detailPairHtml("잔여", remainingAmount(item.remainingAmount)) + detailPair("사용률", percent(item.usagePct)) +
      detailPair("저장 판정", item.resultStatusLabel);

    var details = data.details || [];
    document.getElementById("detail-body").innerHTML = details.length ? details.map(function (detail) {
      var label = CLASSIFICATION_LABEL[detail.classificationSnapshot] || detail.classificationSnapshot;
      var badgeClass = detail.classificationSnapshot === "INCLUDED" ? "status-badge-info" : detail.classificationSnapshot === "REVIEW_REQUIRED" ? "status-badge-review" : "status-badge-neutral";
      return "<tr><td>" + number(detail.detailSeq) + "</td><td>" + escapeHtml(detail.commissionItemName || "—") +
        '</td><td class="text-right tabular-nums">' + won(detail.amount) + '</td><td><span class="status-badge ' + badgeClass + '">' + escapeHtml(label) +
        "</span></td><td>" + escapeHtml(detail.decisionReason || "—") + "</td><td>" + escapeHtml(detail.evidenceRef || "—") + "</td><td>저장 스냅샷</td></tr>";
    }).join("") : '<tr><td colspan="7"><div class="cap-inline-message">저장된 항목별 산입 내역이 없습니다.</div></td></tr>';
    var includedDetailTotal = details.reduce(function (total, detail) {
      return detail.classificationSnapshot === "INCLUDED" ? total + Number(detail.amount || 0) : total;
    }, 0);
    document.getElementById("detail-included").textContent = number(includedDetailTotal);
    var includedNote = document.getElementById("detail-included-note");
    var includedMatches = includedDetailTotal === Number(item.includedAmount);
    includedNote.classList.toggle("cap-detail-mismatch", !includedMatches);
    includedNote.textContent = includedMatches
      ? "제외·검토필요 금액은 합계에 넣지 않습니다 · 저장 산입금액과 일치"
      : "저장 산입금액과 항목별 산입 합계가 일치하지 않습니다.";
  }

  function openDetail(capCheckId) {
    if (state.detailAbortController) state.detailAbortController.abort();
    state.detailAbortController = new AbortController();
    var requestId = ++state.detailRequestId;
    var stateBox = document.getElementById("cap-detail-state");
    var content = document.getElementById("cap-detail-content");
    stateBox.className = "modal-body cap-detail-state";
    stateBox.textContent = "계산근거를 불러오는 중입니다.";
    stateBox.hidden = false;
    content.hidden = true;
    window.FgcUi.modal.open("cap-detail");
    window.FgcUi.apiClient.request("/api/v1/cap-checks/" + encodeURIComponent(capCheckId) + "/details", {
      signal: state.detailAbortController.signal
    })
      .then(function (envelope) {
        if (requestId !== state.detailRequestId) return;
        renderDetail(envelope.data);
        stateBox.hidden = true;
        content.hidden = false;
      })
      .catch(function (error) {
        if (requestId !== state.detailRequestId || (error && error.name === "AbortError")) return;
        stateBox.className = "modal-body cap-detail-state is-error";
        stateBox.textContent = error && error.message ? error.message : "계산근거를 불러오지 못했습니다.";
      });
  }

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    state.page = 1;
    load();
  });
  document.getElementById("cap-reset").addEventListener("click", function () {
    form.reset();
    controls.month.value = root.dataset.initialMonth || "";
    controls.size.value = "20";
    state.page = 1;
    load();
  });
  controls.status.addEventListener("change", function () {
    document.querySelectorAll("[data-cap-status]").forEach(function (button) {
      button.setAttribute("aria-pressed", String(button.dataset.capStatus === controls.status.value));
    });
  });
  controls.size.addEventListener("change", function () {
    state.page = 1;
    load();
  });
  document.getElementById("cap-kpi-grid").addEventListener("click", function (event) {
    var button = event.target.closest("[data-cap-status]");
    if (!button) return;
    controls.status.value = controls.status.value === button.dataset.capStatus ? "" : button.dataset.capStatus;
    state.page = 1;
    load();
  });
  document.getElementById("cap-contract-body").addEventListener("click", function (event) {
    var button = event.target.closest("[data-cap-detail-id]");
    if (button) openDetail(button.dataset.capDetailId);
  });

  queryFromLocation();
  load();
})();
