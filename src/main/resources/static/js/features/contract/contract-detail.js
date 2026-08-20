/*
 * FGC-UI-CONT-W02 보험계약 상세 — 화면 셸(요약 헤더·기본정보·상태이력·탭 제어·상단 액션).
 *
 * detail.html 안에 있던 인라인 <script> 321줄을 그대로 옮긴 파일이다 (#283).
 * 옮기면서 contract-tabs.js 와 겹치던 것을 이쪽 한 곳으로 모았다.
 *   · 경로 파싱 정규식      — 두 파일이 서로 다른 정규식을 쓰고 있었다
 *   · 탭 클릭 리스너        — 같은 버튼에 두 파일이 각각 등록해 제어가 쪼개져 있었다
 *   · 상태 배지 클래스 매핑 — 같은 WARNING 이 화면 위치마다 보라/주황으로 갈렸다
 *   · money() / dateTime()  — js/common/format.js 로 통일 (FGC-SIR-008)
 *
 * contract-tabs.js 는 이 파일이 올려 두는 window.FgcUi.contractDetail 을 읽고,
 * 탭 활성화는 "contract:tab-activate" CustomEvent 로 받는다. 로드 순서는
 * layout/default.html 에서 contract-detail.js → contract-tabs.js 로 고정한다.
 */
(function () {
  "use strict";

  var root = document.querySelector("[data-contract-detail]");
  if (!root) return;

  var format = (window.FgcUi && window.FgcUi.format) || null;
  var EMPTY = "—";

  /* 계약 상세 경로는 여기서만 판단한다. contract-tabs.js 는 아래 contractId 를 읽어 쓴다. */
  var pathMatch = window.location.pathname.match(/^\/contracts\/(\d+)\/?$/);
  var contractId = pathMatch ? pathMatch[1] : null;

  /*
   * 상태 배지 매핑 1벌 (마이그레이션 가이드 §9).
   *
   * 색 배정 근거는 화면정의서 4장 규칙 4 —
   *   정상=초록 / 주의=주황 / 위반·차단=빨강 / 검토필요=review / 진행중=파랑.
   * 그 5색에 들어가지 않는 종료 상태(해지·만기·청약철회)는 neutral 로 둔다.
   *
   * 서버 렌더링 배지(templates/contract/list.html 의 th:classappend)도 같은 결과를 내야 한다.
   * 한쪽을 고치면 반드시 다른 쪽도 같이 고친다.
   */
  var STATUS_BADGE_CLASSES = {
    /* 계약상태 — 화면정의서 4-2 대조표 */
    APPLIED: "status-badge-info",
    ACTIVE: "status-badge-success",
    UNPAID: "status-badge-warning",
    LAPSED: "status-badge-error",
    REVIVED: "status-badge-info",
    CANCELLED: "status-badge-neutral",
    TERMINATED: "status-badge-neutral",
    MATURED: "status-badge-neutral",

    /* 1,200%·차익거래 판정 */
    NORMAL: "status-badge-success",
    WARNING: "status-badge-warning",
    VIOLATION: "status-badge-error",
    REVIEW_REQUIRED: "status-badge-review",

    /* 지급 건 상태 — 화면정의서 4-2 대조표 (작성중·확정·취소) */
    DRAFT: "status-badge-info",
    CONFIRMED: "status-badge-success",

    /* 상태 사건 처리 작업 */
    PENDING: "status-badge-neutral",
    RUNNING: "status-badge-info",
    COMPLETED: "status-badge-success",
    SUCCESS: "status-badge-success",
    SUCCEEDED: "status-badge-success",
    FAILED: "status-badge-error",
    SKIPPED: "status-badge-neutral"
  };

  var STATUS_BADGE_CLASS_NAMES = ["status-badge-info", "status-badge-success", "status-badge-warning",
    "status-badge-error", "status-badge-review", "status-badge-risk", "status-badge-neutral"];

  function badgeClass(status) {
    return STATUS_BADGE_CLASSES[status] || "status-badge-neutral";
  }

  /*
   * 배지 클래스만 갈아 끼운다. className 을 통째로 덮어쓰면 화면 전용 병행 클래스가 사라진다
   * (exception-list.js 와 같은 방식, PublishingTemplateStructureTest 가 강제한다).
   */
  function applyBadge(element, status, label) {
    if (!element) return;
    element.classList.remove.apply(element.classList, STATUS_BADGE_CLASS_NAMES);
    element.classList.add("status-badge", badgeClass(status));
    element.textContent = label || EMPTY;
  }

  /*
   * 코드 → 한글 라벨.
   *
   * 라벨을 JS 에 다시 적지 않는다. detail.html 이 서버 enum 의 label() 을 hidden 요소로
   * 찍어 두고 여기서 읽는다 — ContractStatus.label() 이 유일한 출처가 된다.
   * (인라인 스크립트는 ACTIVE 를 "유지"로 적어 목록 화면의 "정상"과 어긋나 있었다.)
   */
  function labelMap(group) {
    var map = {};
    root.querySelectorAll('[data-label-group="' + group + '"]').forEach(function (item) {
      map[item.dataset.code] = item.textContent;
    });
    return map;
  }

  var contractStatusLabels = labelMap("contractStatus");
  var paymentCycleLabels = labelMap("paymentCycle");
  var dataOriginLabels = labelMap("dataOrigin");
  var processingStatusLabels = labelMap("processingStatus");

  /*
   * 판정·스케줄 상태 라벨 (화면정의서 4-2 대조표). contract-tabs.js 가 만드는 배지도 여기서 읽는다.
   * SIR-008 은 "서버가 라벨을 만든다" 이지만 cap-checks·arbitrage-checks·schedules 응답에는
   * resultStatusLabel 이 없어 코드값이 그대로 노출됐다 — 백엔드 요청 항목으로 남긴다.
   */
  var codeLabels = {
    resultStatus: labelMap("resultStatus"),
    lineStatus: labelMap("lineStatus")
  };

  function label(map, code) {
    return map[code] || code || EMPTY;
  }

  function money(value) {
    return format ? format.won(value) : (value == null ? EMPTY : String(value));
  }

  function dateText(value) {
    return format ? format.date(value) : (value || EMPTY);
  }

  function dateTime(value) {
    return format ? format.dateTime(value) : (value || EMPTY);
  }

  function text(id, value) {
    var element = document.getElementById(id);
    if (element) element.textContent = value === null || value === undefined || value === "" ? EMPTY : value;
  }

  function toast(message, tone, duration) {
    if (window.FgcUi && typeof window.FgcUi.toast === "function") {
      window.FgcUi.toast(message, tone, duration);
    }
  }

  function errorText(error, fallback) {
    return format ? format.errorText(error, fallback) : ((error && error.message) || fallback);
  }

  /* ------------------------------------------------------------------ 기본정보 */

  function populateContract(data) {
    var statusLabel = label(contractStatusLabels, data.contractStatus);
    var originLabel = label(dataOriginLabels, data.dataOrigin);
    var termText = data.paymentTermMonths ? data.paymentTermMonths + "개월" : EMPTY;

    text("contract-summary-title", data.contractNo);
    text("contract-summary-insurer", data.insurerName);
    text("contract-summary-product", data.productName);
    text("contract-summary-date", dateText(data.contractDate));
    text("contract-summary-premium", money(data.monthlyEquivalentFirstPremium));
    text("contract-summary-term", termText);
    text("contract-summary-agent", data.agentName);
    text("contract-summary-organization", data.organizationName);

    applyBadge(document.getElementById("contract-summary-status"), data.contractStatus, statusLabel);
    applyBadge(document.getElementById("contract-summary-origin"), data.dataOrigin, originLabel);

    text("contract-info-number", data.contractNo);
    text("contract-info-product", data.productName);
    text("contract-info-date", dateText(data.contractDate));
    applyBadge(document.getElementById("contract-info-status"), data.contractStatus, statusLabel);
    text("contract-info-cycle-premium", money(data.premiumPerCycleAmount));
    text("contract-info-cycle", label(paymentCycleLabels, data.paymentCycleCode));
    text("contract-info-first-premium", money(data.firstPremiumAmount));
    text("contract-info-monthly-premium", money(data.monthlyEquivalentFirstPremium));
    text("contract-info-term", termText);
    text("contract-info-deduction", money(data.standardSurrenderDeductionAmount));
    applyBadge(document.getElementById("contract-info-origin"), data.dataOrigin, originLabel);
    text("contract-data-fetched-at", dateTime(new Date()));
    setLoadState("계약 정보를 불러왔습니다.", "done");
  }

  /*
   * 로딩·완료·오류를 클래스로 구분한다. 전에는 셋 다 같은 회색 소문단이라
   * 실패했는지 아직 부르는 중인지 구분할 수 없었다.
   */
  function setLoadState(message, tone) {
    var element = document.getElementById("contract-detail-load-state");
    if (!element) return;
    element.classList.remove("is-loading", "is-error", "is-done");
    element.classList.add("is-" + (tone || "loading"));
    element.textContent = message;
  }

  /* ------------------------------------------------------------------ 상태이력 */

  var statusEvents = [];
  var currentStatusSort = "effectiveAt";

  function historyNotice(title, description, tone) {
    var list = document.getElementById("contract-history-list");
    if (!list) return;
    list.replaceChildren();
    var item = document.createElement("li");
    item.className = "contract-history-empty" + (tone === "error" ? " is-error" : "");
    var icon = document.createElement("span");
    icon.className = "material-symbols-rounded";
    icon.setAttribute("aria-hidden", "true");
    icon.textContent = tone === "error" ? "error" : "history";
    var copy = document.createElement("div");
    var strong = document.createElement("strong");
    var paragraph = document.createElement("p");
    strong.textContent = title;
    paragraph.textContent = description;
    copy.append(strong, paragraph);
    item.append(icon, copy);
    list.append(item);
  }

  function processingRow(processing) {
    var row = document.createElement("li");
    row.className = "contract-processing-row";
    var job = document.createElement("span");
    job.className = "contract-processing-job";
    job.textContent = processing.processingJob || "처리 작업";
    var status = document.createElement("span");
    applyBadge(status, processing.processingStatus,
      label(processingStatusLabels, processing.processingStatus) || "처리 대기");
    var processedAt = document.createElement("time");
    processedAt.className = "tabular-nums";
    processedAt.dateTime = processing.processedAt || "";
    processedAt.textContent = dateTime(processing.processedAt);
    row.append(job, status, processedAt);
    return row;
  }

  function historyEvent(event) {
    var item = document.createElement("li");
    item.className = "contract-history-event";
    var marker = document.createElement("span");
    marker.className = "contract-history-marker";
    marker.setAttribute("aria-hidden", "true");
    var card = document.createElement("article");
    card.className = "contract-history-event-card";

    var header = document.createElement("header");
    var title = document.createElement("strong");
    var previousStatus = label(contractStatusLabels, event.previousStatus) === EMPTY
      ? "최초" : label(contractStatusLabels, event.previousStatus);
    var newStatus = label(contractStatusLabels, event.newStatus) === EMPTY
      ? "상태 변경" : label(contractStatusLabels, event.newStatus);
    title.textContent = (event.eventSeq ? "#" + event.eventSeq + " " : "") + previousStatus + " → " + newStatus;
    var source = document.createElement("span");
    var hasProcessings = Array.isArray(event.processings) && event.processings.length > 0;
    source.textContent = (event.sourceSystem || "계약 상태 사건") + (hasProcessings ? "" : " · 처리 대기");
    header.append(title, source);

    var times = document.createElement("dl");
    times.className = "contract-history-times";
    [
      { label: "효력", value: dateTime(event.effectiveAt) },
      { label: "수신", value: dateTime(event.receivedAt) },
      { label: "사유", value: event.reason || EMPTY }
    ].forEach(function (pair) {
      var group = document.createElement("div");
      var term = document.createElement("dt");
      var description = document.createElement("dd");
      term.textContent = pair.label;
      description.className = pair.label === "사유" ? "" : "tabular-nums";
      description.textContent = pair.value;
      description.title = pair.value;
      group.append(term, description);
      times.append(group);
    });

    var processings = document.createElement("ul");
    processings.className = "contract-processing-list";
    if (hasProcessings) {
      event.processings.forEach(function (processing) { processings.append(processingRow(processing)); });
    }

    card.append(header, times, processings);
    item.append(marker, card);
    return item;
  }

  function renderHistory() {
    if (!statusEvents.length) {
      historyNotice("등록된 상태이력이 없습니다.", "상태 사건이 수신되면 효력일시 기준으로 표시됩니다.");
      return;
    }
    var sorted = statusEvents.slice().sort(function (a, b) {
      var left = Date.parse(a[currentStatusSort] || "");
      var right = Date.parse(b[currentStatusSort] || "");
      var leftInvalid = Number.isNaN(left);
      var rightInvalid = Number.isNaN(right);
      if (leftInvalid !== rightInvalid) return leftInvalid ? 1 : -1;
      if (!leftInvalid && left !== right) return left - right;
      return (Number(a.eventSeq) || 0) - (Number(b.eventSeq) || 0);
    });
    var list = document.getElementById("contract-history-list");
    list.replaceChildren();
    sorted.forEach(function (event) { list.append(historyEvent(event)); });
  }

  function loadStatusHistory(apiClient) {
    return apiClient.request("/api/v1/contracts/" + encodeURIComponent(contractId) + "/status-events")
      .then(function (response) {
        var data = response.data;
        statusEvents = Array.isArray(data) ? data : (data && Array.isArray(data.events) ? data.events : []);
        renderHistory();
      })
      .catch(function (error) {
        /* status-events(IF-API-12 계열)는 구현되어 있다. 404 는 이력이 없는 계약이라는 뜻이다. */
        if (error && error.status === 404) {
          historyNotice("등록된 상태이력이 없습니다.", "상태 사건이 수신되면 효력일시 기준으로 표시됩니다.");
          return;
        }
        var message = errorText(error, "상태이력을 불러오지 못했습니다.");
        historyNotice("상태이력을 불러오지 못했습니다.", message, "error");
        toast(message, "error");
      });
  }

  /* ------------------------------------------------------------------ 탭 제어 */

  function tabs() {
    return Array.from(document.querySelectorAll('[role="tab"][aria-controls^="contract-panel-"]'));
  }

  function activateTab(tab) {
    if (!tab) return;
    tabs().forEach(function (candidate) {
      var active = candidate === tab;
      candidate.setAttribute("aria-selected", active ? "true" : "false");
      candidate.tabIndex = active ? 0 : -1;
      var panel = document.getElementById(candidate.getAttribute("aria-controls"));
      if (panel) panel.hidden = !active;
    });
    /* 탭 로딩은 contract-tabs.js 가 맡는다. 리스너를 두 파일이 각각 걸지 않도록 사건으로만 알린다. */
    root.dispatchEvent(new CustomEvent("contract:tab-activate", {
      detail: { name: tab.id.replace("contract-tab-", "") }
    }));
  }

  function initializeTabs() {
    var all = tabs();
    all.forEach(function (tab) {
      tab.addEventListener("click", function () { activateTab(tab); });
      tab.addEventListener("keydown", function (event) {
        var index = all.indexOf(tab);
        var next = null;
        if (event.key === "ArrowRight") next = all[(index + 1) % all.length];
        else if (event.key === "ArrowLeft") next = all[(index - 1 + all.length) % all.length];
        else if (event.key === "Home") next = all[0];
        else if (event.key === "End") next = all[all.length - 1];
        if (!next) return;
        event.preventDefault();
        activateTab(next);
        next.focus();
      });
    });

    var requestedTab = new URLSearchParams(window.location.search).get("tab");
    var requested = requestedTab ? document.getElementById("contract-tab-" + requestedTab) : null;
    if (requested && all.indexOf(requested) >= 0) activateTab(requested);
    else activateTab(all.find(function (tab) { return tab.getAttribute("aria-selected") === "true"; }) || all[0]);
  }

  function initializeSort() {
    var buttons = Array.from(document.querySelectorAll("[data-status-sort]"));
    buttons.forEach(function (button) {
      button.addEventListener("click", function () {
        currentStatusSort = button.getAttribute("data-status-sort");
        buttons.forEach(function (candidate) {
          var active = candidate === button;
          candidate.classList.toggle("is-active", active);
          candidate.setAttribute("aria-pressed", active ? "true" : "false");
        });
        renderHistory();
      });
    });
  }

  /* ------------------------------------------------------------------ 상단 액션 */

  /*
   * 진행 중 표시. disabled 만 걸면 보조기술에는 "그냥 못 누르는 버튼"으로 들린다
   * — reco.js 와 같이 aria-busy 를 함께 세운다.
   */
  function setBusy(element, busy) {
    if (!element) return;
    element.disabled = busy;
    element.classList.toggle("is-loading", busy);
    element.setAttribute("aria-busy", busy ? "true" : "false");
  }

  function reloadTab(name) {
    var panel = document.getElementById("contract-panel-" + name);
    var tab = document.getElementById("contract-tab-" + name);
    if (panel) panel.dataset.loaded = "false";
    if (tab) activateTab(tab);
  }

  function bindAction(button, path, successMessage, failureMessage, reloadTabName) {
    if (!button || button.disabled) return;
    button.addEventListener("click", function () {
      setBusy(button, true);
      window.FgcUi.apiClient.request(path, { method: "POST" })
        .then(function () {
          toast(successMessage, "success");
          reloadTab(reloadTabName);
        })
        .catch(function (error) { toast(errorText(error, failureMessage), "error"); })
        .finally(function () { setBusy(button, false); });
    });
  }

  /*
   * 저장 성공 Toast — CONT-W03 은 저장 후 곧바로 이 화면으로 이동한다.
   * redirect 전에 띄우면 화면 전환으로 사라지므로 CONT-W03 이 sessionStorage 에 남겨 두고
   * 상세 진입 시 여기서 한 번만 꺼내 띄운다(서버 successMessage 를 쓸 수 없는 경로라 대체한다).
   */
  function flushSaveMessage() {
    var raw = null;
    try {
      raw = window.sessionStorage.getItem("fgc.contract.saveMessage");
      if (raw) window.sessionStorage.removeItem("fgc.contract.saveMessage");
    } catch (error) {
      return;
    }
    if (raw) toast(raw, "success");
  }

  /* ------------------------------------------------------------------ 시작 */

  window.FgcUi = window.FgcUi || {};
  window.FgcUi.contractDetail = {
    contractId: contractId,
    root: root,
    codeLabel: function (group, code) { return (codeLabels[group] || {})[code] || null; },
    badgeClass: badgeClass,
    statusBadgeClassNames: STATUS_BADGE_CLASS_NAMES,
    applyBadge: applyBadge
  };

  document.addEventListener("DOMContentLoaded", function () {
    initializeTabs();
    initializeSort();
    flushSaveMessage();

    if (!contractId) {
      setLoadState("계약 식별자를 확인할 수 없습니다.", "error");
      historyNotice("상태이력을 조회할 수 없습니다.", "계약 상세 경로를 확인해 주세요.", "error");
      return;
    }

    text("contract-data-endpoint", "/api/v1/contracts/" + contractId);
    var editButton = document.getElementById("contract-edit-button");
    if (editButton && !editButton.disabled) {
      editButton.addEventListener("click", function () {
        window.location.assign("/contracts/" + encodeURIComponent(contractId) + "/edit");
      });
    }

    var apiClient = window.FgcUi && window.FgcUi.apiClient;
    if (!apiClient) {
      setLoadState("공통 API 클라이언트를 불러오지 못했습니다.", "error");
      historyNotice("상태이력을 불러오지 못했습니다.", "공통 API 클라이언트 로드를 확인해 주세요.", "error");
      return;
    }

    bindAction(document.getElementById("contract-cap-recheck-button"),
      "/api/v1/contracts/" + encodeURIComponent(contractId) + "/cap-check",
      "1,200% 한도 재검증을 완료했습니다.", "한도 재검증에 실패했습니다.", "cap");
    bindAction(document.getElementById("contract-schedule-regenerate-button"),
      "/api/v1/contracts/" + encodeURIComponent(contractId)
        + "/schedules/regenerate?reason=CONTRACT_DETAIL_MANUAL",
      "예상 스케줄을 새 버전으로 재생성했습니다.", "스케줄 재생성에 실패했습니다.", "schedules");

    apiClient.request("/api/v1/contracts/" + encodeURIComponent(contractId))
      .then(function (response) { populateContract(response.data || {}); })
      .catch(function (error) {
        var message = errorText(error, "계약 정보를 불러오지 못했습니다.");
        setLoadState(message, "error");
        toast(message, "error");
      });
    loadStatusHistory(apiClient);
  });
})();
