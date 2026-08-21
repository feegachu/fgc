/*
 * FGC-UI-CONT-W02 탭 6개의 lazy load (IF-API-13~17) 와 1,200% 계산근거 모달.
 *
 * 화면 셸·탭 제어는 contract-detail.js 가 맡는다. 이 파일은
 *   · 계약 ID 를 window.FgcUi.contractDetail 에서 읽고 (경로 정규식을 두 번 쓰지 않는다)
 *   · 탭 활성화를 "contract:tab-activate" 사건으로만 받는다 (같은 버튼에 리스너를 두 번 걸지 않는다)
 * — 두 파일이 같은 요소를 각각 제어하던 구조를 #283 에서 정리했다.
 */
(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var detail = window.FgcUi && window.FgcUi.contractDetail;
  if (!apiClient || !detail || !detail.contractId) return;

  var format = (window.FgcUi && window.FgcUi.format) || null;
  var contractId = detail.contractId;
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

  detail.root.addEventListener("contract:tab-activate", function (event) {
    var name = event.detail && event.detail.name;
    if (loaders[name]) loadTab(name);
  });

  function panelParts(name) {
    var panel = document.getElementById("contract-panel-" + name);
    if (!panel) return null;
    return {
      panel: panel,
      /* 헤더는 건드리지 않고 본문만 교체한다. 패널 전체를 갈아 끼우면 제목·설명이 사라진다. */
      body: panel.querySelector("[data-tab-body]") || panel,
      state: panel.querySelector("[data-tab-state]")
    };
  }

  function setState(parts, message, tone) {
    if (!parts.state) return;
    parts.state.classList.remove("is-loading", "is-error", "is-done");
    parts.state.classList.add("is-" + tone);
    parts.state.textContent = message;
  }

  function loadTab(name, force) {
    var parts = panelParts(name);
    if (!parts || (!force && parts.panel.dataset.loaded === "true")) return;
    parts.panel.setAttribute("aria-busy", "true");
    setState(parts, "불러오는 중입니다.", "loading");
    parts.body.replaceChildren(placeholder("불러오는 중입니다.", "is-loading"));
    loaders[name]().then(function (content) {
      parts.body.replaceChildren(content);
      parts.panel.dataset.loaded = "true";
      setState(parts, "조회를 완료했습니다.", "done");
      scheduleDisclosureSync(parts.body);
    }).catch(function (error) {
      var message = format ? format.errorText(error, "자료를 불러오지 못했습니다.")
        : (error && error.message) || "자료를 불러오지 못했습니다.";
      var wrapper = placeholder(message, "is-error");
      var retry = document.createElement("button");
      retry.type = "button";
      retry.className = "button button-secondary";
      retry.textContent = "다시 시도";
      retry.addEventListener("click", function () { loadTab(name, true); });
      wrapper.appendChild(retry);
      parts.body.replaceChildren(wrapper);
      setState(parts, "불러오지 못했습니다.", "error");
      /* 인라인 오류만 두면 스크롤 밖에서 놓친다 — Toast 를 함께 띄운다 (가이드 §11). */
      if (window.FgcUi && window.FgcUi.toast) window.FgcUi.toast(message, "error");
    }).finally(function () {
      parts.panel.setAttribute("aria-busy", "false");
    });
  }

  /* 로딩·빈·오류가 같은 회색 박스로 보이지 않도록 변형 클래스를 붙인다. */
  function placeholder(message, variant) {
    var wrapper = document.createElement("div");
    wrapper.className = "empty-state contract-tab-state " + variant;
    var paragraph = document.createElement("p");
    paragraph.textContent = message;
    wrapper.appendChild(paragraph);
    return wrapper;
  }

  function request(path, options) {
    return apiClient.request(path, options).then(function (envelope) { return envelope.data; });
  }

  function loadSchedules() {
    return Promise.all(["INSURER_TO_GA", "GA_TO_FC"].map(function (stage) {
      return request("/api/v1/contracts/" + contractId + "/schedules?paymentStage=" + stage)
        .then(function (response) { return { stage: stage, data: response || {} }; });
    })).then(function (responses) {
      var root = document.createDocumentFragment();
      responses.forEach(function (response) {
        var headers = Array.isArray(response.data.headers) ? response.data.headers : [];
        var lines = Array.isArray(response.data.lines) ? response.data.lines : [];
        var activeHeader = headers[0];
        var summary = activeHeader
          ? "버전 " + value(activeHeader.scheduleVersionNo) + " · " + value(activeHeader.lineCount || lines.length) + "회차"
          : "현재 운영 스케줄이 없습니다.";
        var schedule = section(stageLabel(response.stage), summary);
        // 관리자수수료는 같은 회차·항목에 팀장·지사장·본부장 3행이 정상적으로 존재한다(운영정책서 제20조).
        // 수취인·항목을 빼면 FC 행과 구분되지 않아 스케줄이 잘못 만들어진 것처럼 보인다.
        schedule.appendChild(table(stageLabel(response.stage) + " 예상 스케줄",
          ["회차", "예정일", "수수료 항목", "수취인", "예정 지급액", "상태"], lines.map(function (line) {
            return [value(line.installmentNo), date(line.dueDate), value(line.commissionItemName),
              value(line.recipientName), money(line.expectedAmount),
              value(detail.codeLabel("lineStatus", line.lineStatus) || line.lineStatus)];
          }), [2, 3]));
        root.appendChild(schedule);
      });
      return root;
    });
  }

  function loadCapChecks() {
    return request("/api/v1/contracts/" + contractId + "/cap-checks").then(function (rows) {
      rows = Array.isArray(rows) ? rows : [];
      /* "두 규제를 더하지 마세요" 안내는 detail.html 의 guidance-warning 이 고정 노출한다. */
      var root = section("지급단계별 판정", rows.length + "건");
      root.appendChild(table("1,200% 한도 판정",
        ["지급단계", "기준일", "한도", "산입액", "잔여액", "사용률", "판정"],
        rows.map(function (entry) {
          var row = entry.result || {};
          return [stageLabel(row.paymentStage), date(row.asOfDate), money(row.limitAmount),
            capDetailButton(entry.capCheckId, money(row.includedAmount)), money(row.remainingAmount),
            usageGauge(row.usagePct, row.resultStatus),
            statusBadge(row.resultStatusLabel, row.resultStatus, "capResultStatus")];
        })));
      return root;
    });
  }

  function loadArbitrage() {
    return request("/api/v1/contracts/" + contractId + "/arbitrage-checks").then(function (rows) {
      rows = Array.isArray(rows) ? rows : [];
      var root = section("판정 이력", rows.length + "건");
      root.appendChild(table("차익거래 검증 결과",
        ["기준일", "계약차월", "납입보험료", "기지급", "지급예정", "해약환급금", "차액", "판정"],
        rows.map(function (row) {
          return [date(row.asOfDate), value(row.contractMonthNo), money(row.cumulativePaidPremium),
            money(row.paidCommissionAmount), money(row.plannedCommissionAmount),
            money(row.includedSurrenderValueAmount), money(row.netDifferenceAmount),
            statusBadge(row.resultStatusLabel, row.resultStatus, "arbitrageResultStatus")];
        })));
      return root;
    });
  }

  function loadJournals() {
    return request("/api/v1/contracts/" + contractId + "/journals").then(function (rows) {
      rows = Array.isArray(rows) ? rows : [];
      var root = section("분개 목록", rows.length + "건");
      /* 분개번호·유형은 자유 문자열이라 표 폭에서 자주 잘린다 — 전체 보기를 붙인다. */
      root.appendChild(table("이 계약의 분개 목록",
        ["분개번호", "일자", "유형", "지급단계", "차변", "대변", "차액", "상태"],
        rows.map(function (row) {
          return [value(row.journalNo), date(row.journalDate), value(row.journalType),
            value(row.paymentStageLabel || row.paymentStage), money(row.debitTotal), money(row.creditTotal),
            money(row.differenceAmount), statusBadge(row.statusLabel, row.status)];
        }), [0, 2]));
      return root;
    });
  }

  function loadPayments() {
    return request("/api/v1/contracts/" + contractId + "/transactions").then(function (response) {
      var transactions = response && Array.isArray(response.transactions) ? response.transactions : [];
      var reconciliations = response && Array.isArray(response.reconciliations) ? response.reconciliations : [];
      var root = document.createDocumentFragment();
      var paymentSection = section("수수료 지급", transactions.length + "건");
      paymentSection.appendChild(table("수수료 지급 건",
        ["정산월", "지급단계", "금액", "귀속금액", "차액", "상태", "원천"],
        transactions.map(function (row) {
          return [month(row.settlementMonth), value(row.paymentStageLabel || row.paymentStage), money(row.amount),
            money(row.attributionTotal), money(row.differenceAmount),
            statusBadge(row.statusLabel, row.status), value(row.sourceType)];
        }), [6]));
      var recoSection = section("대사 결과", reconciliations.length + "건");
      /* 주원인 코드는 RECO 화면(reco.js)과 같은 방식으로 전체 보기를 붙인다. */
      recoSection.appendChild(table("대사 결과",
        ["결과", "회차", "예상액", "실제액", "차액", "주원인"],
        reconciliations.map(function (row) {
          return [value(row.resultTypeLabel || row.resultType), value(row.installmentNo),
            money(row.expectedTotalAmount), money(row.actualTotalAmount), money(row.differenceAmount),
            value(row.primaryReasonCode)];
        }), [0, 5]));
      root.append(paymentSection, recoSection);
      return root;
    });
  }

  function section(title, summary) {
    var root = document.createElement("section");
    root.className = "contract-tab-section";
    var header = document.createElement("header");
    header.className = "surface-header contract-basic-header";
    var copy = document.createElement("div");
    var heading = document.createElement("h3");
    heading.className = "surface-title";
    heading.textContent = title;
    var description = document.createElement("p");
    description.textContent = summary;
    copy.append(heading, description);
    header.appendChild(copy);
    root.appendChild(header);
    return root;
  }

  /*
   * caption 은 화면에 보이지 않지만 스크린리더에는 표가 무엇인지 알려 준다.
   * disclosureIndexes 는 잘릴 수 있는 자유 텍스트 컬럼 — 실제로 잘린 경우에만
   * "전체 보기"가 뜬다(policy-list.js 선례). 표 셀은 nowrap + ellipsis 라 그것 말고는 볼 방법이 없다.
   */
  function table(caption, headers, rows, disclosureIndexes) {
    if (!rows.length) return placeholder("조회된 자료가 없습니다.", "is-empty");

    var truncatable = disclosureIndexes || [];
    var viewport = document.createElement("div");
    viewport.className = "data-table-viewport contract-tab-viewport";
    viewport.tabIndex = 0;
    viewport.setAttribute("role", "region");
    viewport.setAttribute("aria-label", caption);

    var element = document.createElement("table");
    element.className = "data-table";
    var captionElement = document.createElement("caption");
    captionElement.className = "visually-hidden";
    captionElement.textContent = caption;
    var head = document.createElement("thead");
    var headRow = document.createElement("tr");
    headers.forEach(function (label) {
      var th = document.createElement("th");
      th.scope = "col";
      th.textContent = label;
      headRow.appendChild(th);
    });
    head.appendChild(headRow);
    var body = document.createElement("tbody");
    rows.forEach(function (values) {
      var row = document.createElement("tr");
      values.forEach(function (cellValue, index) {
        if (cellValue instanceof Node) {
          var nodeCell = document.createElement("td");
          nodeCell.appendChild(cellValue);
          row.appendChild(nodeCell);
          return;
        }
        if (truncatable.indexOf(index) >= 0) {
          row.appendChild(disclosureCell(cellValue));
          return;
        }
        var cell = document.createElement("td");
        cell.textContent = value(cellValue);
        /* format.won() 의 음수 표기 "(1,234)원" 에만 매칭 — 잔여액·차액 음수를 빨강으로 (규칙 1, #256 F-A) */
        if (typeof cellValue === "string" && /^\(\d[\d,]*\)원$/.test(cellValue)) {
          cell.classList.add("is-negative-amount");
        }
        row.appendChild(cell);
      });
      body.appendChild(row);
    });
    element.append(captionElement, head, body);
    viewport.appendChild(element);
    return viewport;
  }

  function disclosureCell(input) {
    var cell = capDisclosureCell(input, false);
    /* className 을 덮으면 cap-disclosure-cell 의 .table-cell-details 규칙까지 잃는다. */
    cell.classList.add("contract-disclosure-cell");
    return cell;
  }

  function scheduleDisclosureSync(scope) {
    window.requestAnimationFrame(function () { syncCapDisclosures(scope); });
  }

  var disclosureResizeFrame = null;
  window.addEventListener("resize", function () {
    if (disclosureResizeFrame != null) window.cancelAnimationFrame(disclosureResizeFrame);
    disclosureResizeFrame = window.requestAnimationFrame(function () {
      disclosureResizeFrame = null;
      syncCapDisclosures(document);
    });
  });

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
        window.requestAnimationFrame(function () { syncCapDisclosures(content); });
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
    capText("sum-asof", date(item.asOfDate));
    capText("sum-ruleset", item.capRuleSetId == null ? "—" : "룰셋 ID " + item.capRuleSetId);
    capText("sum-id", item.capCheckId == null ? "—" : "cap_check #" + item.capCheckId);
    var badge = document.getElementById("sum-badge");
    badge.replaceChildren();
    var badgeValue = document.createElement("span");
    badgeValue.className = "status-badge " + capStatusClass(item.resultStatus);
    badgeValue.textContent = value(item.resultStatusLabel || item.resultStatus);
    badge.appendChild(badgeValue);

    var insurerStage = item.paymentStage === "INSURER_TO_GA";
    var multiplier = snapshot.premiumMultiplier == null ? null : String(snapshot.premiumMultiplier);
    var ruleSetLabel = item.capRuleSetId == null ? "—" : "룰셋 ID " + item.capRuleSetId;
    capTableRows("input-body", [
      ["월납환산 초회보험료", money(item.basePremiumAmount)],
      ["한도 배수", multiplier == null ? "—" : multiplier + "배"],
      ["환급금 가산", money(item.refund12mAmount)],
      ["준법경영비 공제", insurerStage ? money(item.complianceDeductionAmount) : "적용하지 않음"],
      ["적용 룰셋", ruleSetLabel]
    ]);
    var limitParts = [int(item.basePremiumAmount), "×", multiplier == null ? "—" : multiplier];
    if (number(item.refund12mAmount) !== 0) {
      limitParts.push("+", int(item.refund12mAmount));
    }
    if (insurerStage && number(item.complianceDeductionAmount) !== 0) {
      limitParts.push("−", int(item.complianceDeductionAmount));
    }
    capText("formula-limit", "한도 = " + limitParts.join(" ") + " = " + money(item.limitAmount));
    capText("formula-usage", "사용률 = " + int(item.includedAmount) + " ÷ "
      + int(item.limitAmount) + " × 100");
    capText("formula-result", "= " + percent(item.usagePct));
    var finalBar = document.getElementById("final-bar");
    finalBar.className = "cap-detail-gauge-bar " + capProgressClass(item.resultStatus);
    finalBar.style.width = capProgressWidth(item.usagePct);
    finalBar.setAttribute("aria-valuenow", String(Math.max(0, Math.min(100, number(item.usagePct)))));
    finalBar.setAttribute("aria-valuetext", "사용률 " + percent(item.usagePct));
    capText("final-usage", "사용률 " + percent(item.usagePct));
    var warningMark = document.getElementById("final-warning-mark");
    var warningUsagePct = Number(snapshot.warningUsagePct);
    warningMark.hidden = !Number.isFinite(warningUsagePct);
    if (!warningMark.hidden) warningMark.style.left = capProgressWidth(warningUsagePct);
    var finalBody = document.getElementById("final-body");
    finalBody.replaceChildren(
      capResultRow("산입 합계", money(item.includedAmount)),
      capResultRow("한도", money(item.limitAmount)),
      capResultRow("잔여", money(item.remainingAmount))
    );
    var finalStatus = document.getElementById("final-status");
    finalStatus.replaceChildren();
    var finalBadge = document.createElement("span");
    finalBadge.className = "status-badge " + capStatusClass(item.resultStatus);
    finalBadge.textContent = value(item.resultStatusLabel || item.resultStatus);
    finalStatus.appendChild(finalBadge);

    var details = Array.isArray(data.details) ? data.details : [];
    capText("detail-count", int(details.length) + "개 항목");
    var detailBody = document.getElementById("detail-body");
    detailBody.replaceChildren();
    if (!details.length) {
      var emptyRow = document.createElement("tr");
      var emptyCell = document.createElement("td");
      emptyCell.colSpan = 6;
      emptyCell.textContent = "저장된 항목별 산입 내역이 없습니다.";
      emptyRow.appendChild(emptyCell);
      detailBody.appendChild(emptyRow);
    } else {
      details.forEach(function (detail) {
        var row = document.createElement("tr");
        var sequenceCell = document.createElement("td");
        sequenceCell.textContent = value(detail.detailSeq);
        var amountCell = document.createElement("td");
        amountCell.className = "text-right tabular-nums";
        amountCell.textContent = money(detail.amount);
        var statusCell = document.createElement("td");
        statusCell.className = "text-center";
        var statusBadge = document.createElement("span");
        statusBadge.className = "status-badge " + capClassificationClass(detail.classificationSnapshot);
        statusBadge.textContent = capClassificationLabel(detail.classificationSnapshot);
        statusCell.appendChild(statusBadge);
        row.append(sequenceCell, capDisclosureCell(detail.commissionItemName, false), amountCell, statusCell,
          capDisclosureCell(detail.decisionReason, false), capDisclosureCell(detail.evidenceRef || "증빙 미연결 / 후속 연결 대기", false));
        detailBody.appendChild(row);
      });
    }
    var includedTotal = details.reduce(function (total, detail) {
      return detail.classificationSnapshot === "INCLUDED" ? total + number(detail.amount) : total;
    }, 0);
    capText("detail-included", int(includedTotal));
    var includedMatches = includedTotal === number(item.includedAmount);
    var includedNote = document.getElementById("detail-included-note");
    includedNote.classList.toggle("cap-detail-mismatch", !includedMatches);
    capText("detail-included-note", includedMatches
      ? "제외 항목 미포함"
      : "저장 산입금액과 항목별 산입 합계가 일치하지 않습니다.");
  }

  function capResultRow(label, displayValue) {
    var row = document.createElement("div");
    var term = document.createElement("dt");
    var description = document.createElement("dd");
    description.className = "tabular-nums";
    term.textContent = label;
    description.textContent = displayValue;
    row.append(term, description);
    return row;
  }

  function capDisclosureCell(input, singleLine) {
    var cell = document.createElement("td");
    cell.className = "cap-disclosure-cell";
    var disclosure = document.createElement("div");
    disclosure.className = "table-cell-disclosure";
    var preview = document.createElement("span");
    preview.className = "table-cell-preview" + (singleLine ? " is-single-line" : "");
    preview.textContent = value(input);
    var details = document.createElement("details");
    details.className = "table-cell-details";
    details.hidden = true;
    var summary = document.createElement("summary");
    summary.innerHTML = '<span class="table-cell-more">전체 보기</span><span class="table-cell-less">접기</span>' +
      '<span class="material-symbols-rounded table-cell-chevron" aria-hidden="true">expand_more</span>';
    var full = document.createElement("p");
    full.className = "table-cell-full";
    full.textContent = value(input);
    details.append(summary, full);
    disclosure.append(preview, details);
    cell.appendChild(disclosure);
    return cell;
  }

  function syncCapDisclosures(scope) {
    scope.querySelectorAll(".table-cell-disclosure").forEach(function (disclosure) {
      var preview = disclosure.querySelector(".table-cell-preview");
      var details = disclosure.querySelector(".table-cell-details");
      if (!preview || !details || preview.clientWidth === 0) return;
      var isTruncated = preview.scrollWidth > preview.clientWidth + 1 || preview.scrollHeight > preview.clientHeight + 1;
      details.hidden = !isTruncated;
      if (!isTruncated) details.open = false;
    });
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
  /* 배지 매핑은 contract-detail.js 한 곳에만 둔다 (가이드 §9). */
  function capStatusClass(status) { return detail.badgeClass(status); }
  /*
   * labelGroup 은 서버가 라벨을 안 내려줄 때만 쓰는 폴백 사전이다.
   * 같은 REVIEW_REQUIRED 라도 1,200% 는 "검토필요", 차익거래는 "자료부족" 이라
   * 부르는 쪽이 어느 판정인지 반드시 지정한다. 분개·지급 건처럼 서버가
   * statusLabel 을 항상 채워 주는 표는 그룹 없이 부른다.
   */
  function statusBadge(labelText, status, labelGroup) {
    var badge = document.createElement("span");
    var fallback = labelGroup ? detail.codeLabel(labelGroup, status) : null;
    detail.applyBadge(badge, status, value(labelText || fallback || status));
    return badge;
  }
  function capClassificationLabel(status) {
    return status === "INCLUDED" ? "산입" : status === "EXCLUDED" ? "제외" : status === "REVIEW_REQUIRED" ? "검토필요" : value(status);
  }
  function capClassificationClass(status) {
    return status === "INCLUDED" ? "status-badge-info" : status === "REVIEW_REQUIRED" ? "status-badge-review" : "status-badge-neutral";
  }
  function capProgressClass(status) {
    return status === "WARNING" ? "is-warning" : status === "VIOLATION" ? "is-violation" : status === "REVIEW_REQUIRED" ? "is-review" : "";
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
  function int(input) { return format ? format.int(input) : String(input); }
  function value(input) { return input === null || input === undefined || input === "" ? "—" : String(input); }
  /* 표시 형식은 js/common/format.js 하나만 쓴다 (FGC-SIR-008). 금액 4벌·날짜 3벌을 여기서 없앴다. */
  function money(input) { return format ? format.won(input) : value(input); }
  function date(input) { return format ? format.date(input) : value(input); }
  function month(input) { return format ? format.month(input) : value(input); }
  /* 사용률은 소수 6자리 — 요율(4자리)과 자리수가 다르다 (인터페이스정의서 2-4). */
  function percent(input) {
    if (input === null || input === undefined || input === "") return "—";
    return (format ? format.usageRate(input) : String(input)) + "%";
  }
  function stageLabel(stage) { return stage === "INSURER_TO_GA" ? "원수사→GA" : stage === "GA_TO_FC" ? "GA→설계사" : value(stage); }
})();
