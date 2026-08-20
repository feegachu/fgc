(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var main = document.querySelector(".reco-page");
  if (!apiClient || !main) return;

  var canProcess = main.dataset.canProcess === "true";

  var el = {
    month: document.getElementById("r-month"),
    stage: document.getElementById("r-stage"),
    insurer: document.getElementById("r-insurer"),
    btnRun: document.getElementById("btn-run"),
    btnBulk: document.getElementById("btn-bulk-exception"),
    typeFilter: document.getElementById("f-type"),
    mismatchOnly: document.getElementById("f-mismatch"),
    resultBody: document.getElementById("result-body"),
    runBody: document.getElementById("run-body"),
    sumTarget: document.getElementById("sum-target"),
    sumMatched: document.getElementById("sum-matched"),
    sumException: document.getElementById("sum-exception"),
    sumRate: document.getElementById("sum-rate"),
    sumDiffTotal: document.getElementById("sum-diff-total"),
    sumRateCard: document.getElementById("sum-rate-card"),
    sumDiffCard: document.getElementById("sum-diff-card"),
    sumExpected: document.getElementById("sum-expected"),
    sumActual: document.getElementById("sum-actual"),
    sumDiff: document.getElementById("sum-diff"),
    resultSumLabel: document.getElementById("result-sum-label"),
    resultPagination: document.getElementById("result-pagination"),
    resultPagePrev: document.getElementById("result-page-prev"),
    resultPageNext: document.getElementById("result-page-next"),
    resultPageIndicator: document.getElementById("result-page-indicator")
  };

  // ③ 결과 목록이 지금 보여주고 있는 실행 — 불일치 예외 일괄 생성 대상이자
  // 결과 필터가 다시 조회할 대상이다. 페이지 로드 시점엔 아무 실행도 안 골랐다.
  var state = {
    reconciliationRunId: null,
    resultsAbortController: null,
    resultsPage: 1,
    compareRequestId: 0
  };

  function number(value) {
    return new Intl.NumberFormat("ko-KR").format(Number(value || 0));
  }

  function won(value) {
    return number(value) + "원";
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  function syncTableCellDisclosures() {
    el.resultBody.querySelectorAll(".table-cell-disclosure").forEach(function (disclosure) {
      var preview = disclosure.querySelector(".table-cell-preview");
      var details = disclosure.querySelector(".table-cell-details");
      if (!preview || !details || preview.clientWidth === 0) return;

      var isTruncated = preview.scrollWidth > preview.clientWidth + 1
        || preview.scrollHeight > preview.clientHeight + 1;
      details.hidden = !isTruncated;
      if (!isTruncated) details.open = false;
    });
  }

  function scheduleDisclosureSync() {
    window.requestAnimationFrame(syncTableCellDisclosures);
  }

  function addOption(select, value, label) {
    var option = document.createElement("option");
    option.value = value == null ? "" : String(value);
    option.textContent = label;
    select.appendChild(option);
  }

  // ---- ① 실행 영역 상태 ----

  function updateRunButtonState() {
    el.btnRun.disabled = !canProcess || !el.insurer.value || !el.stage.value;
  }

  function updateBulkButtonState() {
    el.btnBulk.disabled = !canProcess || !state.reconciliationRunId;
  }

  // ---- 보험회사 드롭다운(전체=이력 조회만, 특정 회사=실행 가능) ----

  function loadInsurers() {
    el.insurer.disabled = true;
    return apiClient.request("/api/v1/base/insurers?page=1&size=100")
      .then(function (envelope) {
        var rows = (envelope.data && envelope.data.content) || [];
        el.insurer.replaceChildren();
        addOption(el.insurer, "", "전체 (실행 이력 조회만)");
        rows.forEach(function (row) {
          addOption(el.insurer, row.insurerId, row.insurerCode + " · " + row.insurerName);
        });
        // 서버가 이미 조회한 조건(insurer 쿼리 파라미터)을 다시 선택 상태로 복원한다 —
        // 옵션은 여기서 비동기로 채워지므로 페이지 로드 시점의 select에는 반영할 수 없다.
        var selectedInsurer = main.dataset.insurerFilter;
        if (selectedInsurer) el.insurer.value = selectedInsurer;
        el.insurer.disabled = false;
        updateRunButtonState();
      })
      .catch(function () {
        window.FgcUi.toast("보험회사 목록을 불러오지 못했습니다.", "error");
      });
  }

  // ---- ① [대사 실행] — IF-API-38 ----

  function findLatestCompletedValidationRun(month) {
    return apiClient.request(
      "/api/v1/validation-runs?month=" + encodeURIComponent(month) + "&status=COMPLETED&page=1&size=1"
    ).then(function (envelope) {
      var content = (envelope.data && envelope.data.content) || [];
      if (!content.length) {
        throw new apiClient.ApiError({
          code: "FGC-RECO-CLIENT-001",
          message: "이 정산월의 월 통합검증이 아직 완료되지 않았습니다. 검증을 먼저 완료하세요."
        }, null, 422);
      }
      return content[0].validationRunId;
    });
  }

  function runErrorMessage(error) {
    return (error && error.message) || "대사 실행에 실패했습니다.";
  }

  function runReconciliation() {
    var month = el.month.value;
    var stage = el.stage.value;
    var insurerId = el.insurer.value;
    if (!month || !stage || !insurerId) return;

    el.btnRun.disabled = true;
    el.btnRun.setAttribute("aria-busy", "true");

    findLatestCompletedValidationRun(month)
      .then(function (validationRunId) {
        return apiClient.request("/api/v1/reconciliations", {
          method: "POST",
          body: {
            settlementMonth: month,
            paymentStage: stage,
            insurerId: Number(insurerId),
            validationRunId: validationRunId
          }
        });
      })
      .then(function (envelope) {
        // ④ 실행 이력은 서버 렌더링(MPA)이라 새 실행을 보려면 새로고침해야 한다 —
        // 새로고침 뒤 이 실행을 자동으로 골라 ③에 결과를 띄우도록 ID만 넘겨 둔다.
        window.FgcUi.toast("대사 실행이 생성됐습니다. 이력에서 결과를 불러옵니다.", "success");
        window.sessionStorage.setItem("reco.autoSelectRunId", String(envelope.data.reconciliationRunId));
        // 새 실행은 항상 이력 1페이지 맨 위에 나온다(created_at desc) — 사용자가 이전에
        // 다른 페이지를 보고 있었다면 그 page 파라미터를 지워야 자동 선택이 성공한다.
        var reloadUrl = new URL(window.location.href);
        reloadUrl.searchParams.delete("page");
        window.location.href = reloadUrl.toString();
      })
      .catch(function (error) {
        window.FgcUi.toast(runErrorMessage(error), "error");
      })
      .finally(function () {
        el.btnRun.setAttribute("aria-busy", "false");
        updateRunButtonState();
      });
  }

  // ---- ③ 결과 목록 — IF-API-40 ----

  // REVIEW_REQUIRED는 "확정된 오류"가 아니라 "비교 자체를 못 해 사람이 봐야 하는" 상태라
  // 다른 불일치(DUPLICATE·AMOUNT_DIFFERENCE 등)와 같은 error 톤으로 묶지 않는다
  // (exception-list.js의 유형별 톤 분리 관례를 따름).
  function resultTypeBadge(item) {
    var tone = "status-badge-error";
    if (item.resultType === "MATCHED") tone = "status-badge-success";
    else if (item.resultType === "REVIEW_REQUIRED") tone = "status-badge-review";
    return '<span class="status-badge ' + tone + '">' + escapeHtml(item.resultTypeLabel) + "</span>";
  }

  function tableCellDisclosure(value, singleLine) {
    var text = String(value == null || value === "" ? "—" : value);
    var safeText = escapeHtml(text);
    return '<div class="table-cell-disclosure">' +
      '<span class="table-cell-preview' + (singleLine ? " is-single-line" : "") + '">' + safeText + "</span>" +
      '<details class="table-cell-details" hidden><summary>' +
      '<span class="table-cell-more">전체 보기</span><span class="table-cell-less">접기</span>' +
      '<span class="material-symbols-rounded table-cell-chevron" aria-hidden="true">expand_more</span>' +
      '</summary><p class="table-cell-full">' + safeText + "</p></details></div>";
  }

  function resultRow(item) {
    var expectedAgentName = (item.expectedAgent && item.expectedAgent.agentName) || "실제 없음";
    var actualAgentName = (item.actualAgent && item.actualAgent.agentName) || "실제 없음";
    var agentMismatch = expectedAgentName !== actualAgentName;
    var agentClass = agentMismatch ? ' class="reco-agent-mismatch"' : "";
    var diffClass = Number(item.differenceAmount || 0) !== 0
      ? ' class="is-number tabular-nums reco-amount-error"'
      : ' class="is-number tabular-nums"';
    var secondaryReasons = (item.secondaryReasons || [])
      .map(function (reason) { return reason.label; })
      .join(", ") || "—";
    return "<tr>" +
      '<td class="tabular-nums">' + escapeHtml(item.contractNo == null || item.contractNo === "" ? "—" : item.contractNo) + "</td>" +
      '<td class="reco-disclosure-cell">' + tableCellDisclosure(item.commissionItemName, false) + "</td>" +
      '<td class="tabular-nums">' + escapeHtml(item.installmentNo) + "</td>" +
      "<td" + agentClass + ">" + escapeHtml(expectedAgentName) + "</td>" +
      "<td" + agentClass + ">" + escapeHtml(actualAgentName) + "</td>" +
      '<td class="is-number tabular-nums">' + won(item.expectedTotalAmount) + "</td>" +
      '<td class="is-number tabular-nums">' + won(item.actualTotalAmount) + "</td>" +
      "<td" + diffClass + ">" + won(item.differenceAmount) + "</td>" +
      '<td class="is-center">' + resultTypeBadge(item) + "</td>" +
      '<td class="reco-disclosure-cell">' + tableCellDisclosure((item.primaryReason && item.primaryReason.label) || "—", false) + "</td>" +
      '<td class="reco-disclosure-cell">' + tableCellDisclosure(secondaryReasons, false) + "</td>" +
      '<td class="is-center"><button class="button button-ghost" type="button" data-reco-compare-id="' +
        escapeHtml(item.reconciliationResultId) + '">보기</button></td>' +
      "</tr>";
  }

  function renderSummary(summary) {
    summary = summary || {};
    el.sumTarget.textContent = number(summary.resultCount);
    el.sumMatched.textContent = number(summary.matchedCount);
    el.sumException.textContent = number(summary.exceptionCount);
    var rate = summary.resultCount ? (summary.matchedCount / summary.resultCount * 100).toFixed(1) : null;
    var differenceTotal = Number(summary.differenceTotal || 0);
    el.sumRate.textContent = rate == null ? "—" : rate;
    el.sumDiffTotal.textContent = number(differenceTotal);
    el.sumRateCard.classList.toggle("kpi-card-success", rate === "100.0");
    el.sumRateCard.classList.toggle("kpi-card-warning", rate !== "100.0");
    el.sumDiffCard.classList.toggle("kpi-card-success", differenceTotal === 0);
    el.sumDiffCard.classList.toggle("kpi-card-error", differenceTotal !== 0);
  }

  function renderResultPagination(items) {
    if (!items || items.totalPages <= 1) {
      el.resultPagination.hidden = true;
      return;
    }
    el.resultPagination.hidden = false;
    el.resultPageIndicator.textContent = items.page + " / " + items.totalPages;
    el.resultPagePrev.disabled = items.page <= 1;
    el.resultPageNext.disabled = items.page >= items.totalPages;
  }

  function renderResults(data) {
    renderSummary(data.summary);
    var rows = (data.items && data.items.content) || [];
    // 필터(결과 유형·불일치만)가 없을 때만 서버가 준 BigDecimal 합계(summary)를 쓴다.
    // 필터가 걸리면 그 조건의 합계를 서버가 안 주므로 현재 페이지 안에서만 클라이언트가 더한다
    // (Number 합산이라 정밀도·페이지 범위 한계가 있음 — "불일치만" 옆 안내 문구 참고).
    var noFilter = !el.typeFilter.value && !el.mismatchOnly.checked;
    el.resultSumLabel.textContent = noFilter ? "합계" : "이 페이지 합계";
    if (!rows.length) {
      el.resultBody.innerHTML = '<tr class="reco-state-row"><td colspan="12"><div class="empty-state">조건에 맞는 자료가 없습니다.</div></td></tr>';
      el.sumExpected.textContent = "0";
      el.sumActual.textContent = "0";
      el.sumDiff.textContent = "0";
      renderResultPagination(noFilter ? data.items : null);
      return;
    }
    el.resultBody.innerHTML = rows.map(resultRow).join("");
    scheduleDisclosureSync();
    if (noFilter) {
      var summary = data.summary || {};
      el.sumExpected.textContent = number(summary.expectedTotal);
      el.sumActual.textContent = number(summary.actualTotal);
      el.sumDiff.textContent = number(summary.differenceTotal);
    } else {
      var totals = rows.reduce(function (acc, row) {
        acc.expected += Number(row.expectedTotalAmount || 0);
        acc.actual += Number(row.actualTotalAmount || 0);
        acc.diff += Number(row.differenceAmount || 0);
        return acc;
      }, { expected: 0, actual: 0, diff: 0 });
      el.sumExpected.textContent = number(totals.expected);
      el.sumActual.textContent = number(totals.actual);
      el.sumDiff.textContent = number(totals.diff);
    }
    renderResultPagination(data.items);
  }

  function loadResults(page) {
    if (!state.reconciliationRunId) return;
    if (state.resultsAbortController) state.resultsAbortController.abort();
    state.resultsAbortController = new AbortController();
    state.resultsPage = page || 1;

    var params = new URLSearchParams();
    params.set("page", String(state.resultsPage));
    if (el.typeFilter.value) params.set("resultType", el.typeFilter.value);

    var url = "/api/v1/reconciliations/" + encodeURIComponent(state.reconciliationRunId) + "/results?" + params.toString();
    apiClient.request(url, { signal: state.resultsAbortController.signal })
      .then(function (envelope) {
        var data = envelope.data;
        if (el.mismatchOnly.checked) {
          data = Object.assign({}, data, {
            items: Object.assign({}, data.items, {
              content: data.items.content.filter(function (item) { return item.resultType !== "MATCHED"; })
            })
          });
        }
        renderResults(data);
      })
      .catch(function (error) {
        if (error && error.name === "AbortError") return;
        window.FgcUi.toast((error && error.message) || "대사 결과를 불러오지 못했습니다.", "error");
      });
  }

  // ---- ④ 실행 이력 행 클릭 → ③에 그 실행 결과 로드 ----

  function selectRun(reconciliationRunId) {
    state.reconciliationRunId = reconciliationRunId;
    updateBulkButtonState();
    loadResults(1);
    document.querySelectorAll(".reco-history-row").forEach(function (row) {
      var isSelected = row.dataset.reconciliationRunId === String(reconciliationRunId);
      row.classList.toggle("is-selected", isSelected);
      row.setAttribute("aria-selected", String(isSelected));
    });
  }

  // ---- RECO-W02 비교 상세 모달 — IF-API-41 ----

  function paymentStageLabel(value) {
    if (value === "INSURER_TO_GA") return "원수사→GA";
    if (value === "GA_TO_FC") return "GA→설계사";
    return value || "지급단계 미확인";
  }

  function settlementMonth(value) {
    return value ? String(value).slice(0, 7) : "—";
  }

  function agentLabel(agent) {
    if (!agent) return "없음";
    var name = agent.agentName || "이름 미확인";
    return agent.agentCode ? name + " · " + agent.agentCode : name;
  }

  function sourceRow(label, value, tone) {
    var valueClass = "tabular-nums" + (tone ? " " + tone : "");
    return "<div class=\"reco-compare-source-row\"><dt>" + escapeHtml(label) +
      '</dt><dd class="' + valueClass + '">' + escapeHtml(value) + "</dd></div>";
  }

  function expectedSource(match, data, index) {
    var sourceLabel = match.scheduleLineId == null ? "schedule_line —" : "schedule_line #" + match.scheduleLineId;
    return '<section class="reco-compare-source-entry">' +
      (index > 0 ? '<h4 class="reco-compare-source-entry-title">예상 원천 ' + (index + 1) + " · " + escapeHtml(sourceLabel) + "</h4>" : "") +
      '<dl class="reco-compare-source-fields">' +
      sourceRow("회차", match.installmentNo == null ? "—" : match.installmentNo + "회차") +
      sourceRow("지급예정일", match.dueDate || "—") +
      sourceRow("수령자", agentLabel(data.expectedAgent)) +
      sourceRow("기준금액", won(match.basisAmount)) +
      sourceRow("적용 요율", match.ratePct == null ? "—" : match.ratePct + "%") +
      sourceRow("예상 금액", won(match.matchedAmount)) +
      "</dl>" +
      '<p class="reco-compare-source-note">' + escapeHtml(sourceLabel) + " · 저장된 대사 원천</p></section>";
  }

  function actualSource(match, data, index) {
    var sourceLabel = match.commissionTransactionId == null
      ? "commission_transaction —"
      : "commission_transaction #" + match.commissionTransactionId;
    var attributionLabel = match.transactionAttributionId == null
      ? "—"
      : "attribution #" + match.transactionAttributionId;
    return '<section class="reco-compare-source-entry">' +
      (index > 0 ? '<h4 class="reco-compare-source-entry-title">실제 원천 ' + (index + 1) + " · " + escapeHtml(sourceLabel) + "</h4>" : "") +
      '<dl class="reco-compare-source-fields">' +
      sourceRow("회차", match.installmentNo == null ? "—" : match.installmentNo + "회차") +
      sourceRow("정산월", settlementMonth(match.settlementMonth)) +
      sourceRow("수령자", agentLabel(data.actualAgent)) +
      sourceRow("귀속 ID", attributionLabel) +
      sourceRow("실제 금액", won(match.matchedAmount), Number(data.differenceAmount || 0) !== 0 ? "reco-amount-error" : "") +
      sourceRow("지급일", match.referenceDate || match.attributionDate || "—") +
      "</dl>" +
      '<p class="reco-compare-source-note">' + escapeHtml(sourceLabel) + " · 저장된 대사 원천</p></section>";
  }

  function renderCompareModal(data) {
    var snapshot = data.detailSnapshot || {};
    var stage = paymentStageLabel(snapshot.paymentStage);
    document.getElementById("h-eyebrow").textContent =
      "대사 결과 #" + data.reconciliationResultId + " · " + stage;
    document.getElementById("h-contract").textContent = data.contractNo || "—";
    document.getElementById("h-item").textContent = data.commissionItemName || "—";
    document.getElementById("h-inst").textContent = data.installmentNo == null ? "—" : data.installmentNo;
    document.getElementById("h-type").innerHTML = resultTypeBadge(data);
    document.getElementById("h-id").textContent = data.reconciliationResultId;
    document.getElementById("h-key").textContent = data.matchGroupKey || "—";
    document.getElementById("h-reason").textContent = (data.primaryReason && data.primaryReason.label) || "—";
    document.getElementById("h-diff").textContent = won(data.differenceAmount);
    document.getElementById("h-summary-diff").textContent = won(data.differenceAmount);

    var matches = data.matches || [];
    var expected = matches.filter(function (m) { return m.matchRole === "EXPECTED" || m.matchRole === "BOTH"; });
    var actual = matches.filter(function (m) { return m.matchRole === "ACTUAL" || m.matchRole === "BOTH"; });
    document.getElementById("expected-source-id").textContent = expected.length === 1 && expected[0].scheduleLineId != null
      ? "schedule_line #" + expected[0].scheduleLineId
      : expected.length + "개 원천";
    document.getElementById("actual-source-id").textContent = actual.length === 1 && actual[0].commissionTransactionId != null
      ? "commission_transaction #" + actual[0].commissionTransactionId
      : actual.length + "개 원천";
    document.getElementById("expected-body").innerHTML = expected.length
      ? expected.map(function (match, index) { return expectedSource(match, data, index); }).join("")
      : '<div class="empty-state reco-compare-empty">예상 없음</div>';
    document.getElementById("actual-body").innerHTML = actual.length
      ? actual.map(function (match, index) { return actualSource(match, data, index); }).join("")
      : '<div class="empty-state reco-compare-empty">실제 없음</div>';

    var secondaryReasons = (data.secondaryReasons || [])
      .map(function (reason) { return reason.label; })
      .join(", ") || "—";
    document.getElementById("diff-expected").textContent = won(data.expectedTotalAmount);
    document.getElementById("diff-actual").textContent = won(data.actualTotalAmount);
    document.getElementById("diff-result-type").innerHTML = resultTypeBadge(data);
    document.getElementById("diff-primary-reason").textContent =
      (data.primaryReason && data.primaryReason.label) || "—";
    document.getElementById("diff-secondary-reason").textContent = secondaryReasons;
    document.getElementById("difference-formula").textContent =
      "차액 = 실제 " + won(data.actualTotalAmount) + " − 예상 " + won(data.expectedTotalAmount) +
      " = " + won(data.differenceAmount);
    document.getElementById("tolerance-note").textContent = "허용오차 0원 · 시스템 계산 결과";

    var links = document.getElementById("links");
    links.innerHTML = "";
    var scheduleHeaderId = matches
      .map(function (m) { return m.scheduleHeaderId; })
      .filter(function (id) { return id != null; })[0];
    if (scheduleHeaderId != null) {
      links.innerHTML += '<a class="button button-secondary" href="/schedules/' +
        encodeURIComponent(scheduleHeaderId) + '">스케줄 상세</a>';
    }
    links.innerHTML += '<a class="button button-secondary" href="/transactions">지급 건 목록</a>';
    links.innerHTML += '<a class="button button-secondary" href="/journals">관련 분개</a>';
    links.innerHTML += '<a class="button button-secondary" href="/exceptions">예외 건</a>';
  }

  function setCompareModalState(mode, message) {
    var stateBox = document.getElementById("reco-compare-state");
    var content = document.getElementById("reco-compare-content");
    if (mode === "content") {
      stateBox.hidden = true;
      content.hidden = false;
      return;
    }
    stateBox.className = "modal-body empty-state reco-compare-state" + (mode === "error" ? " is-error" : "");
    stateBox.textContent = message;
    stateBox.hidden = false;
    content.hidden = true;
  }

  function openCompareModal(reconciliationResultId) {
    var requestId = ++state.compareRequestId;
    setCompareModalState("loading", "불러오는 중입니다.");
    window.FgcUi.modal.open("reco-compare");
    apiClient.request("/api/v1/reconciliations/results/" + encodeURIComponent(reconciliationResultId))
      .then(function (envelope) {
        if (requestId !== state.compareRequestId) return;
        renderCompareModal(envelope.data);
        setCompareModalState("content");
      })
      .catch(function (error) {
        if (requestId !== state.compareRequestId) return;
        setCompareModalState("error", (error && error.message) || "비교 상세를 불러오지 못했습니다.");
      });
  }

  // ---- [불일치 예외 일괄 생성] — IF-API-42 ----

  function bulkExceptionErrorMessage(error) {
    return (error && error.message) || "예외 일괄 생성에 실패했습니다.";
  }

  function bulkCreateExceptions() {
    if (!state.reconciliationRunId) return;
    el.btnBulk.disabled = true;
    el.btnBulk.setAttribute("aria-busy", "true");
    apiClient.request("/api/v1/reconciliations/" + encodeURIComponent(state.reconciliationRunId) + "/exceptions", {
      method: "POST"
    })
      .then(function (envelope) {
        var data = envelope.data;
        window.FgcUi.toast(
          "예외 " + number(data.created) + "건 생성, " + number(data.skippedDuplicate) + "건은 이미 있어 건너뜀.",
          "success"
        );
      })
      .catch(function (error) {
        window.FgcUi.toast(bulkExceptionErrorMessage(error), "error");
      })
      .finally(function () {
        el.btnBulk.setAttribute("aria-busy", "false");
        updateBulkButtonState();
      });
  }

  // ---- 이벤트 바인딩 ----

  el.insurer.addEventListener("change", updateRunButtonState);
  el.stage.addEventListener("change", updateRunButtonState);
  el.btnRun.addEventListener("click", runReconciliation);
  el.btnBulk.addEventListener("click", bulkCreateExceptions);
  el.typeFilter.addEventListener("change", function () { loadResults(1); });
  el.mismatchOnly.addEventListener("change", function () { loadResults(1); });
  el.resultPagePrev.addEventListener("click", function () { loadResults(state.resultsPage - 1); });
  el.resultPageNext.addEventListener("click", function () { loadResults(state.resultsPage + 1); });
  el.resultBody.addEventListener("click", function (event) {
    var button = event.target.closest("[data-reco-compare-id]");
    if (button) openCompareModal(button.dataset.recoCompareId);
  });
  el.runBody.addEventListener("click", function (event) {
    var row = event.target.closest(".reco-history-row");
    if (row) selectRun(row.dataset.reconciliationRunId);
  });
  el.runBody.addEventListener("keydown", function (event) {
    if (event.key !== "Enter" && event.key !== " ") return;
    if (event.target.closest("a, button, details, input, select, textarea")) return;
    var row = event.target.closest(".reco-history-row");
    if (!row) return;
    event.preventDefault();
    selectRun(row.dataset.reconciliationRunId);
  });

  updateRunButtonState();
  updateBulkButtonState();
  loadInsurers();
  window.addEventListener("load", scheduleDisclosureSync, { once: true });
  window.addEventListener("resize", scheduleDisclosureSync);
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(scheduleDisclosureSync);
  }

  var autoSelectRunId = window.sessionStorage.getItem("reco.autoSelectRunId");
  if (autoSelectRunId) {
    window.sessionStorage.removeItem("reco.autoSelectRunId");
    if (document.querySelector('.reco-history-row[data-reconciliation-run-id="' + autoSelectRunId + '"]')) {
      selectRun(autoSelectRunId);
    }
  }

  // EXCP-W01의 대사 불일치 참조는 실행 이력을 먼저 선택할 필요 없이 결과 PK로
  // 비교 상세를 직접 연다. 결과가 없어진 경우에는 기존 API 오류 안내를 그대로 쓴다.
  var deepLinkedResultId = main.dataset.deepLinkedResultId;
  if (deepLinkedResultId) {
    openCompareModal(deepLinkedResultId);
  }
})();
