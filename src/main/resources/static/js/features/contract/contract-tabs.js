(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var pathMatch = window.location.pathname.match(/^\/contracts\/(\d+)\/?$/);
  if (!apiClient || !pathMatch) return;

  var contractId = pathMatch[1];
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

  function request(path) {
    return apiClient.request(path).then(function (envelope) { return envelope.data; });
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
      root.appendChild(table(["지급단계", "기준일", "한도", "산입액", "잔여액", "사용률", "판정", "근거"],
        rows.map(function (entry) {
          var row = entry.result || {};
          return [stageLabel(row.paymentStage), value(row.asOfDate), money(row.limitAmount),
            money(row.includedAmount), money(row.remainingAmount), percent(row.usagePct),
            value(row.resultStatus), link("계산근거", "/cap-checks?capCheckId=" + entry.capCheckId)];
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
  function value(input) { return input === null || input === undefined || input === "" ? "—" : String(input); }
  function money(input) { var number = Number(input); return Number.isFinite(number) ? number.toLocaleString("ko-KR") + "원" : "—"; }
  function percent(input) { var number = Number(input); return Number.isFinite(number) ? number.toLocaleString("ko-KR", { maximumFractionDigits: 6 }) + "%" : "—"; }
  function stageLabel(stage) { return stage === "INSURER_TO_GA" ? "원수사→GA" : stage === "GA_TO_FC" ? "GA→설계사" : value(stage); }
})();
