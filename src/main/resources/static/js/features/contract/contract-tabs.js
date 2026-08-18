(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var pathMatch = window.location.pathname.match(/^\/contracts\/(\d+)\/?$/);
  if (!apiClient || !pathMatch) return;

  var contractId = pathMatch[1];
  var capDetailAbortController = null;
  var capDetailRequestId = 0;
  var CAP_PROGRESS_CLASS = {
    NORMAL: "",
    WARNING: "is-warning",
    VIOLATION: "is-violation",
    REVIEW_REQUIRED: "is-review"
  };
  var loaders = {
    schedules: loadSchedules,
    cap: loadCapChecks,
    arbitrage: loadArbitrage,
    ledger: loadJournals,
    payment: loadPayments
  };

  document.querySelectorAll(".contract-detail-tab").forEach(function (tab) {
    var name = tab.id.replace("contract-tab-", "");
    if (!loaders[name]) return;
    tab.addEventListener("click", function () { loadTab(name); });
  });

  document.addEventListener("DOMContentLoaded", function () {
    Object.keys(loaders).forEach(function (name) {
      var tab = document.getElementById("contract-tab-" + name);
      if (tab && tab.classList.contains("is-active")) loadTab(name);
    });
  });

  function loadTab(name, force) {
    var panel = document.getElementById("contract-panel-" + name);
    if (!panel || (!force && panel.dataset.loaded === "true")) return;
    renderState(panel, "불러오는 중입니다.", false);
    loaders[name]().then(function (content) {
      panel.replaceChildren(content);
      panel.dataset.loaded = "true";
    }).catch(function (error) {
      var wrapper = document.createElement("div");
      wrapper.className = "empty-state";
      var message = document.createElement("p");
      message.textContent = error && error.message ? error.message : "자료를 불러오지 못했습니다.";
      var retry = document.createElement("button");
      retry.type = "button";
      retry.className = "button button-secondary";
      retry.textContent = "다시 시도";
      retry.addEventListener("click", function () { loadTab(name, true); });
      wrapper.append(message, retry);
      panel.replaceChildren(wrapper);
    });
  }

  function renderState(panel, message) {
    var state = document.createElement("div");
    state.className = "empty-state";
    state.textContent = message;
    panel.replaceChildren(state);
  }

  function request(path, options) {
    return apiClient.request(path, options).then(function (envelope) { return envelope.data; });
  }

  function loadSchedules() {
    return Promise.all(["INSURER_TO_GA", "GA_TO_FC"].map(function (stage) {
      return request("/api/v1/contracts/" + contractId + "/schedules?paymentStage=" + stage);
    })).then(function (responses) {
      var headers = [];
      var lines = [];
      responses.forEach(function (response) {
        headers = headers.concat(response && Array.isArray(response.headers) ? response.headers : []);
        lines = lines.concat(response && Array.isArray(response.lines) ? response.lines : []);
      });
      var root = section("예상 스케줄", headers.length + "개 지급단계 · " + lines.length + "개 회차");
      root.appendChild(table([
        "지급단계", "버전", "상태", "회차 수", "예상 총액", "정책버전", "상세"
      ], headers.map(function (row) {
        return [stageLabel(row.paymentStage), "v" + value(row.scheduleVersionNo), value(row.status),
          value(row.lineCount), money(row.expectedTotal), value(row.policyVersionLabel),
          link("회차 보기", "/schedules/" + row.scheduleHeaderId)];
      })));
      return root;
    });
  }

  function loadCapChecks() {
    return request("/api/v1/contracts/" + contractId + "/cap-checks").then(function (rows) {
      rows = Array.isArray(rows) ? rows : [];
      var root = section("1,200% 한도 판정", "지급단계별 최신 판정");
      var note = document.createElement("p");
      note.className = "contract-lazy-note";
      note.textContent = "원수사→GA와 GA→설계사 한도는 서로 다른 규제이므로 합산하지 않습니다.";
      root.appendChild(note);
      root.appendChild(table(["지급단계", "기준일", "한도", "산입액", "잔여액", "사용률", "판정"],
        rows.map(function (entry) {
          var row = entry.result || {};
          return [stageLabel(row.paymentStage), value(row.asOfDate), money(row.limitAmount),
            capDetailButton(entry.capCheckId, money(row.includedAmount)), money(row.remainingAmount),
            usageGauge(row.usagePct, row.resultStatus), value(row.resultStatus)];
        })));
      return root;
    });
  }

  function loadArbitrage() {
    return request("/api/v1/contracts/" + contractId + "/arbitrage-checks").then(function (rows) {
      rows = Array.isArray(rows) ? rows : [];
      var root = section("차익거래 검증", rows.length + "건");
      root.appendChild(table(["기준일", "계약차월", "납입보험료", "기지급", "지급예정", "해약환급금", "차액", "판정"],
        rows.map(function (row) {
          return [value(row.asOfDate), value(row.contractMonthNo), money(row.cumulativePaidPremium),
            money(row.paidCommissionAmount), money(row.plannedCommissionAmount),
            money(row.includedSurrenderValueAmount), money(row.netDifferenceAmount), value(row.resultStatus)];
        })));
      return root;
    });
  }

  function loadJournals() {
    return request("/api/v1/contracts/" + contractId + "/journals").then(function (rows) {
      rows = Array.isArray(rows) ? rows : [];
      var root = section("원장", rows.length + "개 분개");
      root.appendChild(table(["분개번호", "일자", "유형", "지급단계", "차변", "대변", "차액", "상태"],
        rows.map(function (row) {
          return [value(row.journalNo), value(row.journalDate), value(row.journalType),
            value(row.paymentStageLabel || row.paymentStage), money(row.debitTotal), money(row.creditTotal),
            money(row.differenceAmount), value(row.statusLabel || row.status)];
        })));
      return root;
    });
  }

  function loadPayments() {
    return request("/api/v1/contracts/" + contractId + "/transactions").then(function (response) {
      var transactions = response && Array.isArray(response.transactions) ? response.transactions : [];
      var reconciliations = response && Array.isArray(response.reconciliations) ? response.reconciliations : [];
      var root = document.createDocumentFragment();
      var paymentSection = section("수수료 지급", transactions.length + "건");
      paymentSection.appendChild(table(["정산월", "지급단계", "금액", "귀속금액", "차액", "상태", "원천"],
        transactions.map(function (row) {
          return [value(row.settlementMonth), value(row.paymentStageLabel || row.paymentStage), money(row.amount),
            money(row.attributionTotal), money(row.differenceAmount), value(row.statusLabel || row.status), value(row.sourceType)];
        })));
      var recoSection = section("대사 결과", reconciliations.length + "건");
      recoSection.appendChild(table(["결과", "회차", "예상액", "실제액", "차액", "주원인"],
        reconciliations.map(function (row) {
          return [value(row.resultTypeLabel || row.resultType), value(row.installmentNo),
            money(row.expectedTotalAmount), money(row.actualTotalAmount), money(row.differenceAmount),
            value(row.primaryReasonCode)];
        })));
      root.append(paymentSection, recoSection);
      return root;
    });
  }

  function section(title, summary) {
    var root = document.createElement("section");
    var header = document.createElement("header");
    header.className = "contract-basic-header";
    var copy = document.createElement("div");
    var heading = document.createElement("h2");
    heading.className = "surface-title";
    heading.textContent = title;
    var description = document.createElement("p");
    description.textContent = summary;
    copy.append(heading, description);
    header.appendChild(copy);
    root.appendChild(header);
    return root;
  }

  function table(headers, rows) {
    if (!rows.length) {
      var empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "조회된 자료가 없습니다.";
      return empty;
    }
    var viewport = document.createElement("div");
    viewport.className = "data-table-viewport";
    var element = document.createElement("table");
    element.className = "data-table";
    var head = document.createElement("thead");
    var headRow = document.createElement("tr");
    headers.forEach(function (label) { var th = document.createElement("th"); th.textContent = label; headRow.appendChild(th); });
    head.appendChild(headRow);
    var body = document.createElement("tbody");
    rows.forEach(function (values) {
      var row = document.createElement("tr");
      values.forEach(function (cellValue) {
        var cell = document.createElement("td");
        if (cellValue instanceof Node) cell.appendChild(cellValue); else cell.textContent = value(cellValue);
        row.appendChild(cell);
      });
      body.appendChild(row);
    });
    element.append(head, body);
    viewport.appendChild(element);
    return viewport;
  }

  function link(label, href) { var anchor = document.createElement("a"); anchor.textContent = label; anchor.href = href; return anchor; }
  function capDetailButton(capCheckId, label) {
    var button = document.createElement("button");
    button.type = "button";
    button.className = "cap-basis-button";
    button.textContent = label;
    button.addEventListener("click", function () { openCapDetail(capCheckId); });
    return button;
  }

  function openCapDetail(capCheckId) {
    if (!window.FgcUi || !window.FgcUi.modal) return;
    if (capDetailAbortController) capDetailAbortController.abort();
    capDetailAbortController = new AbortController();
    var requestId = ++capDetailRequestId;
    var state = document.getElementById("cap-detail-state");
    var content = document.getElementById("cap-detail-content");
    state.className = "modal-body cap-detail-state";
    state.textContent = "계산근거를 불러오는 중입니다.";
    state.hidden = false;
    content.hidden = true;
    window.FgcUi.modal.open("cap-detail");
    request("/api/v1/cap-checks/" + encodeURIComponent(capCheckId) + "/details", { signal: capDetailAbortController.signal })
      .then(function (data) {
        if (requestId !== capDetailRequestId) return;
        renderCapDetail(data || {});
        state.hidden = true;
        content.hidden = false;
      })
      .catch(function (error) {
        if (requestId !== capDetailRequestId || (error && error.name === "AbortError")) return;
        state.className = "modal-body cap-detail-state is-error";
        state.textContent = error && error.message ? error.message : "계산근거를 불러오지 못했습니다.";
      });
  }

  function renderCapDetail(data) {
    var item = data.capCheck || {};
    var snapshot = data.calculationSnapshot || {};
    capText("sum-contract", item.contractNo);
    capText("sum-stage", item.paymentStageLabel || stageLabel(item.paymentStage));
    capText("sum-asof", item.asOfDate);
    capText("sum-kind", item.checkKind || "저장 판정");
    capText("sum-ruleset", item.capRuleSetId == null ? "—" : "ID " + item.capRuleSetId);
    capText("sum-id", item.capCheckId);
    var badge = document.getElementById("sum-badge");
    badge.replaceChildren();
    var badgeValue = document.createElement("span");
    badgeValue.className = "status-badge " + capStatusClass(item.resultStatus);
    badgeValue.textContent = value(item.resultStatusLabel || item.resultStatus);
    badge.appendChild(badgeValue);

    var insurerStage = item.paymentStage === "INSURER_TO_GA";
    capTableRows("input-body", [
      ["월납환산 초회보험료", money(item.basePremiumAmount)],
      ["12차월 환급금 가산", number(item.refund12mAmount) === 0 ? "해당없음" : money(item.refund12mAmount)],
      ["준법경영비 공제", insurerStage ? money(item.complianceDeductionAmount) : "적용하지 않음"],
      ["보험료 배수", snapshot.premiumMultiplier == null ? "—" : snapshot.premiumMultiplier]
    ]);
    capText("formula", insurerStage
      ? "기준 보험료 × 배수 + 환급금 가산 − 준법경영비 공제"
      : "기준 보험료 × 배수 + 환급금 가산");
    capText("formula-note", insurerStage
      ? "원수사 → GA 단계의 저장된 공제 금액을 표시합니다."
      : "GA → 설계사 단계에는 준법경영비 공제를 적용하지 않습니다.");
    var finalBar = document.getElementById("final-bar");
    finalBar.style.width = capProgressWidth(item.usagePct);
    capText("final-limit-label", "한도 " + money(item.limitAmount));
    capTableRows("final-body", [
      ["한도", money(item.limitAmount)], ["산입금액", money(item.includedAmount)],
      ["잔여", money(item.remainingAmount)], ["사용률", percent(item.usagePct)],
      ["저장 판정", value(item.resultStatusLabel || item.resultStatus)]
    ]);

    var details = Array.isArray(data.details) ? data.details : [];
    var detailBody = document.getElementById("detail-body");
    detailBody.replaceChildren();
    if (!details.length) {
      var emptyRow = document.createElement("tr");
      var emptyCell = document.createElement("td");
      emptyCell.colSpan = 7;
      emptyCell.textContent = "저장된 항목별 산입 내역이 없습니다.";
      emptyRow.appendChild(emptyCell);
      detailBody.appendChild(emptyRow);
    } else {
      details.forEach(function (detail) {
        var row = document.createElement("tr");
        [detail.detailSeq, detail.commissionItemName, money(detail.amount), capClassificationLabel(detail.classificationSnapshot),
          detail.decisionReason, detail.evidenceRef, "저장 스냅샷"].forEach(function (entry) {
          var cell = document.createElement("td");
          cell.textContent = value(entry);
          row.appendChild(cell);
        });
        detailBody.appendChild(row);
      });
    }
    var includedTotal = details.reduce(function (total, detail) {
      return detail.classificationSnapshot === "INCLUDED" ? total + number(detail.amount) : total;
    }, 0);
    capText("detail-included", includedTotal.toLocaleString("ko-KR"));
    capText("detail-included-note", includedTotal === number(item.includedAmount)
      ? "제외·검토필요 금액은 합계에 넣지 않습니다 · 저장 산입금액과 일치"
      : "저장 산입금액과 항목별 산입 합계가 일치하지 않습니다.");
  }

  function capText(id, input) { document.getElementById(id).textContent = value(input); }
  function capTableRows(id, rows) {
    var body = document.getElementById(id);
    body.replaceChildren();
    rows.forEach(function (entry) {
      var row = document.createElement("tr");
      var heading = document.createElement("th");
      heading.scope = "row";
      heading.textContent = entry[0];
      var cell = document.createElement("td");
      cell.className = "text-right tabular-nums";
      cell.textContent = value(entry[1]);
      row.append(heading, cell);
      body.appendChild(row);
    });
  }
  function capStatusClass(status) {
    return status === "NORMAL" ? "status-badge-success" : status === "WARNING" ? "status-badge-warning"
      : status === "VIOLATION" ? "status-badge-error" : status === "REVIEW_REQUIRED" ? "status-badge-review" : "status-badge-neutral";
  }
  function capClassificationLabel(status) {
    return status === "INCLUDED" ? "산입" : status === "EXCLUDED" ? "제외" : status === "REVIEW_REQUIRED" ? "검토필요" : value(status);
  }
  function capProgressWidth(input) { return Math.max(0, Math.min(100, number(input))) + "%"; }
  function usageGauge(input, status) {
    var usage = Number(input);
    if (!Number.isFinite(usage)) return value(input);
    var cell = document.createElement("div");
    cell.className = "cap-usage-cell";
    var track = document.createElement("span");
    track.className = "cap-usage-track";
    track.setAttribute("role", "progressbar");
    track.setAttribute("aria-valuemin", "0");
    track.setAttribute("aria-valuemax", "100");
    track.setAttribute("aria-valuenow", String(usage));
    track.setAttribute("aria-label", "한도 사용률 " + percent(usage));
    var bar = document.createElement("span");
    bar.className = "cap-usage-bar " + (CAP_PROGRESS_CLASS[status] || "");
    bar.style.setProperty("--cap-progress", Math.max(0, Math.min(100, usage)) + "%");
    track.appendChild(bar);
    var text = document.createElement("span");
    text.className = "cap-usage-value";
    text.textContent = percent(usage);
    cell.append(track, text);
    return cell;
  }
  function number(input) {
    var parsed = Number(input);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  function value(input) { return input === null || input === undefined || input === "" ? "—" : String(input); }
  function money(input) { var number = Number(input); return Number.isFinite(number) ? number.toLocaleString("ko-KR") + "원" : "—"; }
  function percent(input) { var number = Number(input); return Number.isFinite(number) ? number.toLocaleString("ko-KR", { maximumFractionDigits: 6 }) + "%" : "—"; }
  function stageLabel(stage) { return stage === "INSURER_TO_GA" ? "원수사→GA" : stage === "GA_TO_FC" ? "GA→설계사" : value(stage); }
})();
