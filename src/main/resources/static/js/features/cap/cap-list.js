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
    orgId: document.getElementById("cap-organization"),
    contractNo: document.getElementById("cap-contract-no"),
    size: document.getElementById("cap-page-size")
  };
  var state = {
    page: 1,
    abortController: null,
    detailAbortController: null,
    detailRequestId: 0,
    initialReferenceValues: {}
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

  /*
   * 음수 표기는 괄호가 숫자만 감싸고 "원" 은 밖이다 — (1,200,000)원.
   * 공통 유틸 FgcUi.format.won() 과 같은 형태다 (PR #293 팀 결정).
   * 화면마다 표기가 갈리지 않도록 여기서만 바꾸지 말 것.
   */
  function remainingAmount(value) {
    var parsed = Number(value);
    if (parsed < 0) {
      return '<span class="cap-negative-amount">(' + number(Math.abs(parsed)) + ")원</span>";
    }
    return won(value);
  }

  function percent(value) {
    return value == null || value === "" ? "—" : String(value) + "%";
  }

  function tableCellDisclosure(value, singleLine, previewHtml) {
    var text = value == null || value === "" ? "—" : String(value);
    var safeText = escapeHtml(text);
    return '<div class="table-cell-disclosure">' +
      '<span class="table-cell-preview' + (singleLine ? " is-single-line" : "") + '">' + (previewHtml || safeText) + '</span>' +
      '<details class="table-cell-details" hidden><summary>' +
      '<span class="table-cell-more">전체 보기</span><span class="table-cell-less">접기</span>' +
      '<span class="material-symbols-rounded table-cell-chevron" aria-hidden="true">expand_more</span>' +
      '</summary><p class="table-cell-full">' + safeText + '</p></details></div>';
  }

  function syncTableCellDisclosures(scope) {
    (scope || document).querySelectorAll(".table-cell-disclosure").forEach(function (disclosure) {
      var preview = disclosure.querySelector(".table-cell-preview");
      var details = disclosure.querySelector(".table-cell-details");
      if (!preview || !details || preview.clientWidth === 0) return;
      var isTruncated = preview.scrollWidth > preview.clientWidth + 1 || preview.scrollHeight > preview.clientHeight + 1;
      details.hidden = !isTruncated;
      if (!isTruncated) details.open = false;
    });
  }

  function scheduleDisclosureSync(scope) {
    window.requestAnimationFrame(function () { syncTableCellDisclosures(scope); });
  }

  function visualWidth(value) {
    var parsed = Number(value);
    if (!Number.isFinite(parsed) || parsed <= 0) return "0%";
    return Math.min(parsed, 100) + "%";
  }

  function accessibleProgress(value) {
    var parsed = Number(value);
    if (!Number.isFinite(parsed)) return 0;
    return Math.max(0, Math.min(parsed, 100));
  }

  function queryFromLocation() {
    var params = new URLSearchParams(window.location.search);
    ["month", "stage", "status", "contractNo"].forEach(function (key) {
      if (!params.has(key) || !controls[key]) return;
      controls[key].value = params.get(key);
    });
    ["insurerId", "orgId"].forEach(function (key) {
      if (params.has(key)) state.initialReferenceValues[key] = params.get(key);
    });
    if (!controls.month.value) controls.month.value = root.dataset.initialMonth || "";
    if (params.has("page")) state.page = Math.max(1, Number(params.get("page")) || 1);
    if (params.has("size")) controls.size.value = params.get("size");
  }

  function currentParams() {
    var params = new URLSearchParams();
    ["month", "stage", "status", "insurerId", "orgId", "contractNo"].forEach(function (key) {
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

  function asOfDate() {
    if (controls.month.value) return controls.month.value + "-01";
    var today = new Date();
    return new Date(today.getTime() - today.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  }

  function content(envelope) {
    return envelope && envelope.data && Array.isArray(envelope.data.content) ? envelope.data.content : [];
  }

  function setOptions(select, rows, valueKey, label, selectedValue) {
    selectedValue = selectedValue == null ? select.value : selectedValue;
    select.replaceChildren();
    var all = document.createElement("option");
    all.value = "";
    all.textContent = "전체";
    select.appendChild(all);
    rows.forEach(function (row) {
      var option = document.createElement("option");
      option.value = String(row[valueKey]);
      option.textContent = label(row);
      option.disabled = row.activeYn === false;
      select.appendChild(option);
    });
    select.value = selectedValue;
    select.disabled = false;
  }

  function setReferenceLoadError(select, label, error) {
    select.replaceChildren();
    var option = document.createElement("option");
    option.value = "";
    option.textContent = label + " 목록을 불러오지 못했습니다.";
    select.appendChild(option);
    select.disabled = true;
    if (window.FgcUi && window.FgcUi.toast) {
      window.FgcUi.toast(error && error.message ? error.message : label + " 조회 실패", "error");
    }
  }

  function loadInsurers() {
    controls.insurerId.disabled = true;
    return window.FgcUi.apiClient.request("/api/v1/base/insurers?page=1&size=100")
      .then(function (envelope) {
        setOptions(controls.insurerId, content(envelope), "insurerId", function (row) {
          return row.insurerCode + " · " + row.insurerName;
        }, state.initialReferenceValues.insurerId);
        delete state.initialReferenceValues.insurerId;
      })
      .catch(function (error) { setReferenceLoadError(controls.insurerId, "보험회사", error); });
  }

  function loadOrganizations() {
    controls.orgId.disabled = true;
    return window.FgcUi.apiClient.request("/api/v1/base/organizations?asOf=" + encodeURIComponent(asOfDate()) + "&page=1&size=100")
      .then(function (envelope) {
        setOptions(controls.orgId, content(envelope), "organizationId", function (row) {
          return row.organizationCode + " · " + row.organizationName;
        }, state.initialReferenceValues.orgId);
        delete state.initialReferenceValues.orgId;
      })
      .catch(function (error) { setReferenceLoadError(controls.orgId, "조직", error); });
  }

  function loadReferenceData() {
    return Promise.all([loadInsurers(), loadOrganizations()]);
  }

  function setLoading() {
    var kpiGrid = document.getElementById("cap-kpi-grid");
    var stageGrid = document.getElementById("cap-stage-grid");
    kpiGrid.setAttribute("aria-busy", "true");
    stageGrid.setAttribute("aria-busy", "true");
    kpiGrid.classList.add("is-loading");
    stageGrid.classList.add("is-loading");
    document.getElementById("cap-agent-message").textContent = "설계사별 모니터링 지표를 불러오는 중입니다.";
    document.getElementById("cap-agent-message").hidden = false;
    document.getElementById("cap-agent-table-wrap").hidden = true;
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
    var stageGrid = document.getElementById("cap-stage-grid");
    stageGrid.setAttribute("aria-busy", "false");
    document.getElementById("cap-kpi-grid").classList.remove("is-loading");
    stageGrid.classList.remove("is-loading");
    stageGrid.innerHTML = '<div class="cap-inline-message is-error">지급단계별 집계를 불러오지 못했습니다.</div>';
    document.getElementById("cap-agent-message").textContent = "설계사별 모니터링 지표를 불러오지 못했습니다.";
    document.getElementById("cap-agent-message").hidden = false;
    document.getElementById("cap-agent-table-wrap").hidden = true;
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
    document.getElementById("cap-kpi-grid").classList.remove("is-loading");
  }

  function statusBadge(item) {
    return '<span class="status-badge ' + (STATUS_CLASS[item.resultStatus] || "status-badge-neutral") + '">' + escapeHtml(item.resultStatusLabel || item.resultStatus || "—") + '</span>';
  }

  function contractRow(item) {
    var isAgent = item.paymentStage === "GA_TO_FC";
    var deduction = isAgent ? '<span class="cap-not-applicable">적용하지 않음</span>' : won(item.complianceDeductionAmount);
    var contractHref = "/contracts/" + encodeURIComponent(item.contractId) + "?tab=cap";
    var contractLink = '<a class="cap-contract-link" href="' + contractHref + '">' + escapeHtml(item.contractNo) + '</a>';
    return "<tr>" +
      '<td class="cap-disclosure-cell">' + tableCellDisclosure(item.contractNo, true, contractLink) + '</td>' +
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
      scheduleDisclosureSync(tableWrap);
    }
    renderPagination(data.page, data.totalPages);
  }

  function stageStatus(stage) {
    if (!stage.contractCount) return { label: "대상 없음", className: "status-badge-neutral", progressClass: "" };
    if (stage.violationCount > 0) return { label: "위반 " + number(stage.violationCount) + "건", className: "status-badge-error", progressClass: "is-violation" };
    if (stage.warningCount > 0) return { label: "주의 " + number(stage.warningCount) + "건", className: "status-badge-warning", progressClass: "is-warning" };
    if (stage.reviewRequiredCount > 0) return { label: "검토필요 " + number(stage.reviewRequiredCount) + "건", className: "status-badge-review", progressClass: "is-review" };
    return { label: "정상", className: "status-badge-success", progressClass: "" };
  }

  function stageCard(stage) {
    var isAgent = stage.paymentStage === "GA_TO_FC";
    var status = stageStatus(stage);
    var deduction = isAgent ? "적용하지 않음" : won(stage.complianceDeductionAmountTotal);
    var worst = stage.worstContractNo
      ? "최고 사용률 " + escapeHtml(stage.worstContractNo) + " · " + escapeHtml(percent(stage.worstUsagePct))
      : "판정 대상 계약이 없습니다.";
    return '<article class="cap-stage-card">' +
      '<header class="cap-stage-card-header"><div><h3 class="cap-stage-title">' + escapeHtml(stage.paymentStageLabel) + '</h3><p class="cap-stage-code">' + escapeHtml(stage.paymentStage) + '</p></div><span class="status-badge ' + status.className + '">' + status.label + '</span></header>' +
      '<div class="cap-stage-metrics"><div><span>계약 수</span><strong>' + number(stage.contractCount) + '건</strong></div><div><span>한도 합계</span><strong>' + won(stage.limitAmountTotal) + '</strong></div><div><span>산입 합계</span><strong>' + won(stage.includedAmountTotal) + '</strong></div></div>' +
      '<div class="cap-stage-usage"><div><span>사용률</span><strong>' + escapeHtml(percent(stage.usagePct)) + '</strong></div><span class="cap-usage-track"><span class="cap-usage-bar ' + status.progressClass + '" style="--cap-progress:' + visualWidth(stage.usagePct) + '"></span></span></div>' +
      '<p class="cap-stage-note">' + (isAgent ? "준법경영비 공제를 적용하지 않습니다." : "준법경영비 공제 합계 " + deduction) + '<br>' + worst + '</p></article>';
  }

  function renderStageSummary(rows) {
    var grid = document.getElementById("cap-stage-grid");
    grid.setAttribute("aria-busy", "false");
    grid.classList.remove("is-loading");
    if (!rows || !rows.length) {
      grid.innerHTML = '<div class="cap-inline-message">조건에 맞는 지급단계별 집계가 없습니다.</div>';
      return;
    }
    grid.innerHTML = rows.map(stageCard).join("");
  }

  function agentRow(agent) {
    var organization = [agent.organizationCode, agent.organizationName].filter(Boolean).join(" · ") || "—";
    var status = stageStatus(agent);
    var agentLabel = [agent.agentName || "—", agent.agentCode].filter(Boolean).join(" · ");
    return "<tr>" +
      '<td class="cap-disclosure-cell">' + tableCellDisclosure(agentLabel, false) + '</td>' +
      '<td class="cap-disclosure-cell">' + tableCellDisclosure(organization, false) + '</td>' +
      '<td class="text-right tabular-nums">' + number(agent.contractCount) + '건</td>' +
      '<td class="text-right tabular-nums">' + won(agent.limitAmountTotal) + '</td>' +
      '<td class="text-right tabular-nums">' + won(agent.includedAmountTotal) + '</td>' +
      '<td><div class="cap-usage-cell"><span class="cap-usage-track"><span class="cap-usage-bar ' + status.progressClass + '" style="--cap-progress:' + visualWidth(agent.usagePct) + '"></span></span><span class="cap-usage-value">' + escapeHtml(percent(agent.usagePct)) + '</span></div></td>' +
      '<td><span class="status-badge ' + status.className + '">' + status.label + '</span></td></tr>';
  }

  function renderAgentSummary(rows) {
    var message = document.getElementById("cap-agent-message");
    var tableWrap = document.getElementById("cap-agent-table-wrap");
    if (!rows || !rows.length) {
      message.textContent = "조건에 맞는 GA → 설계사 단계 모니터링 대상이 없습니다.";
      message.hidden = false;
      tableWrap.hidden = true;
      return;
    }
    document.getElementById("cap-agent-body").innerHTML = rows.map(agentRow).join("");
    message.hidden = true;
    tableWrap.hidden = false;
    scheduleDisclosureSync(tableWrap);
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
    renderStageSummary(data.stageSummary);
    renderContracts(data);
    renderAgentSummary(data.agentSummary);
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

  function renderDetail(data) {
    var item = data.capCheck;
    var snapshot = data.calculationSnapshot || {};
    document.getElementById("sum-contract").textContent = item.contractNo || "—";
    document.getElementById("sum-stage").textContent = item.paymentStageLabel || "—";
    document.getElementById("sum-asof").textContent = item.asOfDate || "—";
    document.getElementById("sum-ruleset").textContent = item.capRuleSetId == null ? "—" : "룰셋 ID " + item.capRuleSetId;
    document.getElementById("sum-id").textContent = item.capCheckId == null ? "—" : "cap_check #" + item.capCheckId;
    document.getElementById("sum-badge").innerHTML = statusBadge(item);

    var insurerStage = item.paymentStage === "INSURER_TO_GA";
    var multiplier = snapshot.premiumMultiplier == null ? null : String(snapshot.premiumMultiplier);
    var ruleSetLabel = item.capRuleSetId == null ? "—" : "룰셋 ID " + item.capRuleSetId;
    document.getElementById("input-body").innerHTML =
      detailPair("월납환산 초회보험료", won(item.basePremiumAmount)) +
      detailPair("한도 배수", multiplier == null ? "—" : multiplier + "배") +
      detailPair("환급금 가산", won(item.refund12mAmount)) +
      detailPair("준법경영비 공제", insurerStage ? won(item.complianceDeductionAmount) : "적용하지 않음") +
      detailPair("적용 룰셋", ruleSetLabel);

    var limitParts = [number(item.basePremiumAmount), "×", multiplier == null ? "—" : multiplier];
    if (Number(item.refund12mAmount) !== 0) limitParts.push("+", number(item.refund12mAmount));
    if (insurerStage && Number(item.complianceDeductionAmount) !== 0) {
      limitParts.push("−", number(item.complianceDeductionAmount));
    }
    document.getElementById("formula-limit").textContent =
      "한도 = " + limitParts.join(" ") + " = " + won(item.limitAmount);
    document.getElementById("formula-usage").textContent =
      "사용률 = " + number(item.includedAmount) + " ÷ " + number(item.limitAmount) + " × 100";
    document.getElementById("formula-result").textContent = "= " + percent(item.usagePct);

    var finalBar = document.getElementById("final-bar");
    finalBar.className = "cap-detail-gauge-bar " + (STATUS_PROGRESS_CLASS[item.resultStatus] || "");
    finalBar.style.width = visualWidth(item.usagePct);
    finalBar.setAttribute("aria-valuenow", String(accessibleProgress(item.usagePct)));
    finalBar.setAttribute("aria-valuetext", "사용률 " + percent(item.usagePct));
    document.getElementById("final-usage").textContent = "사용률 " + percent(item.usagePct);
    var warningMark = document.getElementById("final-warning-mark");
    var warningUsagePct = Number(snapshot.warningUsagePct);
    warningMark.hidden = !Number.isFinite(warningUsagePct);
    if (!warningMark.hidden) warningMark.style.left = visualWidth(warningUsagePct);
    document.getElementById("final-body").innerHTML =
      '<div><dt>산입 합계</dt><dd class="tabular-nums">' + won(item.includedAmount) + "</dd></div>" +
      '<div><dt>한도</dt><dd class="tabular-nums">' + won(item.limitAmount) + "</dd></div>" +
      '<div><dt>잔여</dt><dd class="tabular-nums">' + remainingAmount(item.remainingAmount) + "</dd></div>";
    document.getElementById("final-status").innerHTML = statusBadge(item);

    var details = data.details || [];
    document.getElementById("detail-count").textContent = number(details.length) + "개 항목";
    document.getElementById("detail-body").innerHTML = details.length ? details.map(function (detail) {
      var label = CLASSIFICATION_LABEL[detail.classificationSnapshot] || detail.classificationSnapshot;
      var badgeClass = detail.classificationSnapshot === "INCLUDED" ? "status-badge-info" : detail.classificationSnapshot === "REVIEW_REQUIRED" ? "status-badge-review" : "status-badge-neutral";
      return "<tr><td>" + number(detail.detailSeq) + '</td><td class="cap-disclosure-cell">' + tableCellDisclosure(detail.commissionItemName, false) +
        '</td><td class="text-right tabular-nums">' + won(detail.amount) + '</td><td class="text-center"><span class="status-badge ' + badgeClass + '">' + escapeHtml(label) +
        '</span></td><td class="cap-disclosure-cell">' + tableCellDisclosure(detail.decisionReason, false) +
        '</td><td class="cap-disclosure-cell">' + tableCellDisclosure(detail.evidenceRef || "증빙 미연결 / 후속 연결 대기", false) + "</td></tr>";
    }).join("") : '<tr><td colspan="6"><div class="cap-inline-message">저장된 항목별 산입 내역이 없습니다.</div></td></tr>';
    var includedDetailTotal = details.reduce(function (total, detail) {
      return detail.classificationSnapshot === "INCLUDED" ? total + Number(detail.amount || 0) : total;
    }, 0);
    document.getElementById("detail-included").textContent = number(includedDetailTotal);
    var includedNote = document.getElementById("detail-included-note");
    var includedMatches = includedDetailTotal === Number(item.includedAmount);
    includedNote.classList.toggle("cap-detail-mismatch", !includedMatches);
    includedNote.textContent = includedMatches
      ? "제외 항목 미포함"
      : "저장 산입금액과 항목별 산입 합계가 일치하지 않습니다.";
    scheduleDisclosureSync(document.getElementById("cap-detail-content"));
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
    loadReferenceData().then(load);
  });
  document.getElementById("cap-reset").addEventListener("click", function () {
    form.reset();
    controls.month.value = root.dataset.initialMonth || "";
    controls.size.value = "20";
    state.page = 1;
    loadReferenceData().then(load);
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
  loadReferenceData().then(load);
  window.addEventListener("resize", function () { scheduleDisclosureSync(root); });
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(function () { scheduleDisclosureSync(root); });
  }
})();
