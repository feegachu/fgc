(function () {
  "use strict";

  var root = document.querySelector(".base-page");
  var baseApi = window.FgcUi && window.FgcUi.baseApi;
  if (!root || !baseApi) return;

  var DEFAULT_PAGE_SIZE = 20;
  var OPTION_PAGE_SIZE = 100;
  var TAB_KEYS = ["organization", "insurer", "product", "agent", "commission-item"];
  var state = {
    activeTab: "organization",
    pages: { organization: 1, insurer: 1, product: 1, agent: 1 },
    requests: {},
    loaded: {},
    insurerOptionsLoaded: false,
    organizationOptionsAsOf: null,
    organizationOptionsInitialized: false,
    organizationOptionsRequestId: 0
  };
  var tabs = Array.from(root.querySelectorAll("[data-base-tab]"));
  var panels = Array.from(root.querySelectorAll("[data-base-panel]"));
  var initialAsOf = root.dataset.initialAsOf || "";
  var CASHFLOW_BADGES = {
    PAYMENT: ["지급", "status-badge-success"],
    DEDUCTION: ["차감", "status-badge-warning"]
  };
  var ITEM_CATEGORY_LABELS = {
    SALES: "모집수수료",
    MAINTENANCE: "유지관리",
    INCENTIVE: "판매촉진",
    MANAGEMENT: "관리자수수료",
    SUPPORT: "지원",
    COST: "공통비",
    ADJUSTMENT: "조정",
    CLAWBACK: "환수",
    RECOVERY: "회수"
  };
  var ITEM_CODE_CATEGORY_LABELS = {
    SETTLEMENT_SUPPORT: "정착지원",
    NEWCOMER_SUPPORT: "신인지원"
  };

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  function display(value, fallback) {
    return value === null || value === undefined || value === "" ? (fallback || "—") : escapeHtml(value);
  }

  function tableCellDisclosure(value, limit, singleLine) {
    var text = value === null || value === undefined || value === "" ? "—" : String(value);
    var safeText = escapeHtml(text);
    if (text.length <= limit) return safeText;
    return '<div class="table-cell-disclosure">' +
      '<span class="table-cell-preview' + (singleLine ? " is-single-line" : "") + '">' + safeText + "</span>" +
      '<details class="table-cell-details" hidden><summary>' +
      '<span class="table-cell-more">전체 보기</span><span class="table-cell-less">접기</span>' +
      '<span class="material-symbols-rounded table-cell-chevron" aria-hidden="true">expand_more</span>' +
      '</summary><p class="table-cell-full">' + safeText + "</p></details></div>";
  }

  /* 실제로 잘린 셀에만 컨트롤을 노출한다 (policy-list.js·exception-list.js 와 같은 방식).
     text.length 만으로는 실제 화면 폭 대비 잘림 여부와 어긋날 수 있어 scrollWidth로 다시 검증한다. */
  function syncTableCellDisclosures(scope) {
    (scope || document).querySelectorAll(".table-cell-disclosure").forEach(function (disclosure) {
      var preview = disclosure.querySelector(".table-cell-preview");
      var details = disclosure.querySelector(".table-cell-details");
      if (!preview || !details || preview.clientWidth === 0) return;

      var isTruncated = preview.scrollWidth > preview.clientWidth + 1
        || preview.scrollHeight > preview.clientHeight + 1;
      details.hidden = !isTruncated;
      if (!isTruncated) details.open = false;
    });
  }

  function scheduleDisclosureSync(scope) {
    window.requestAnimationFrame(function () {
      syncTableCellDisclosures(scope);
    });
  }

  function booleanLabel(value) {
    return value === true ? "예" : value === false ? "아니오" : "—";
  }

  function statusBadge(isActive, activeLabel, inactiveLabel) {
    return '<span class="status-badge ' + (isActive ? "status-badge-success" : "status-badge-neutral") + '">' +
      escapeHtml(isActive ? activeLabel : inactiveLabel) + "</span>";
  }

  function cashflowBadge(value) {
    var badge = CASHFLOW_BADGES[value] || [value || "—", "status-badge-neutral"];
    return '<span class="status-badge ' + badge[1] + '">' + escapeHtml(badge[0]) + "</span>";
  }

  function commissionItemCategory(item) {
    return ITEM_CODE_CATEGORY_LABELS[item.itemCode] || ITEM_CATEGORY_LABELS[item.itemCategory] || item.itemCategory;
  }

  function panelElement(key, type) {
    return root.querySelector('[data-base-' + type + '="' + key + '"]');
  }

  function formFor(key) {
    return root.querySelector('[data-base-form="' + key + '"]');
  }

  function formParams(key) {
    var form = formFor(key);
    var params = {};
    if (!form) return params;
    new FormData(form).forEach(function (value, name) {
      if (String(value).trim()) params[name] = String(value).trim();
    });
    if (key !== "commission-item") {
      params.page = state.pages[key] || 1;
      params.size = DEFAULT_PAGE_SIZE;
    }
    return params;
  }

  function clearPagination(key) {
    var pagination = panelElement(key, "pagination");
    if (pagination) pagination.replaceChildren();
  }

  function setMessage(key, message, isError) {
    var element = panelElement(key, "message");
    if (!element) return;
    element.textContent = message;
    element.classList.toggle("is-error", Boolean(isError));
    element.setAttribute("role", isError ? "alert" : "status");
    element.hidden = false;
  }

  function setLoading(key) {
    root.querySelector('[data-base-panel="' + key + '"]').setAttribute("aria-busy", "true");
    setMessage(key, "기준정보를 불러오는 중입니다.", false);
    panelElement(key, "table").hidden = true;
    clearPagination(key);
  }

  function setError(key, error) {
    var message = error && error.message ? error.message : "기준정보를 불러오지 못했습니다.";
    if (error && error.requestId) message += " (요청 ID: " + error.requestId + ")";
    setMessage(key, message, true);
    panelElement(key, "table").hidden = true;
    panelElement(key, "count").textContent = "0";
    clearPagination(key);
    root.querySelector('[data-base-panel="' + key + '"]').setAttribute("aria-busy", "false");
  }

  function organizationRow(item) {
    return "<tr>" +
      '<td class="tabular-nums base-disclosure-cell">' + tableCellDisclosure(item.organizationCode, 20, true) + "</td>" +
      '<td class="base-disclosure-cell">' + tableCellDisclosure(item.organizationName, 24, true) + "</td>" +
      "<td>" + display(item.organizationTypeLabel) + ' <span class="base-code-label">' + display(item.organizationType) + "</span></td>" +
      '<td class="base-disclosure-cell">' + tableCellDisclosure(item.parentName, 24, true) + "</td>" +
      '<td class="tabular-nums">' + display(item.effectiveFrom) + "</td>" +
      '<td class="tabular-nums">' + display(item.effectiveTo) + "</td>" +
      "<td>" + statusBadge(item.activeYn, "사용", "사용중지") + "</td></tr>";
  }

  function insurerRow(item) {
    return "<tr>" +
      '<td class="tabular-nums base-disclosure-cell">' + tableCellDisclosure(item.insurerCode, 20, true) + "</td>" +
      '<td class="base-disclosure-cell">' + tableCellDisclosure(item.insurerName, 24, true) + "</td>" +
      "<td>" + display(item.insurerTypeLabel) + ' <span class="base-code-label">' + display(item.insurerType) + "</span></td>" +
      "<td>" + statusBadge(item.activeYn, "사용", "사용중지") + "</td></tr>";
  }

  function productRow(item) {
    var salesPeriod = display(item.salesStartDate) + " ~ " + display(item.salesEndDate, "현재");
    var documentVersion = display(item.basicDocumentVersion) + '<span class="base-secondary-line">' + display(item.basicDocumentDate) + "</span>";
    return "<tr>" +
      '<td class="tabular-nums base-disclosure-cell">' + tableCellDisclosure(item.insurerProductCode, 20, true) + "</td>" +
      '<td class="base-disclosure-cell">' + tableCellDisclosure(item.productName, 28, false) + "</td>" +
      '<td class="tabular-nums base-disclosure-cell">' + tableCellDisclosure(item.standardProductCode, 20, true) + "</td>" +
      "<td>" + display(item.productGroupCode) + "</td>" +
      '<td class="tabular-nums">' + display(item.offeringVersion) + "</td>" +
      '<td class="tabular-nums">' + salesPeriod + "</td>" +
      '<td class="tabular-nums">' + documentVersion + "</td>" +
      "<td>" + display(item.channelCode) + (item.channelSpecialRuleYn ? '<span class="base-secondary-line">채널 특례</span>' : "") + "</td>" +
      '<td class="tabular-nums">' + display(item.feeRegimeCode) + "</td>" +
      "<td>" + (item.standardDeduction80Yn
        ? '<span class="status-badge status-badge-warning">예</span>'
        : '<span class="status-badge status-badge-neutral">아니오</span>') + "</td></tr>";
  }

  function agentRow(item) {
    var isActiveStatus = item.agentStatus === "ACTIVE";
    var career = item.priorThreeYearExperienceYn === null || item.priorThreeYearExperienceYn === undefined
      ? "확인 전" : booleanLabel(item.priorThreeYearExperienceYn);
    var newcomer = item.newcomerSupportEligibleYn
      ? '<span class="status-badge status-badge-warning">대상</span><span class="base-secondary-line">' + display(item.newcomerSupportEndDate) + "까지</span>"
      : '<span class="status-badge status-badge-neutral">비대상</span>';
    return "<tr>" +
      '<td class="tabular-nums base-disclosure-cell">' + tableCellDisclosure(item.agentCode, 20, true) + "</td>" +
      "<td>" + display(item.agentName) + "</td>" +
      "<td>" + display(item.rankLabel) + ' <span class="base-code-label">' + display(item.rankCode) + "</span></td>" +
      '<td class="base-disclosure-cell">' + tableCellDisclosure(item.organizationName, 24, true) + '<span class="base-secondary-line tabular-nums">' + display(item.organizationCode) + "</span></td>" +
      '<td class="tabular-nums">' + display(item.appointmentDate) + "</td>" +
      '<td class="tabular-nums">' + display(item.terminationDate) + "</td>" +
      "<td>" + statusBadge(isActiveStatus, item.agentStatusLabel || "활동", item.agentStatusLabel || "비활동") +
        (item.activeYn ? "" : '<span class="base-secondary-line">기준정보 사용중지</span>') + "</td>" +
      '<td class="tabular-nums">' + display(item.latestRegistrationDate) + "</td>" +
      "<td>" + escapeHtml(career) + "</td>" +
      "<td>" + newcomer + "</td></tr>";
  }

  function commissionItemRow(item) {
    return "<tr>" +
      '<td class="tabular-nums base-disclosure-cell">' + tableCellDisclosure(item.itemCode, 18, true) + "</td>" +
      '<td class="base-disclosure-cell">' + tableCellDisclosure(item.itemName, 28, false) + "</td>" +
      "<td>" + cashflowBadge(item.cashflowType) + "</td>" +
      "<td>" + display(commissionItemCategory(item)) + "</td>" +
      '<td class="tabular-nums">' + display(item.effectiveFrom) + "</td>" +
      '<td class="tabular-nums">' + display(item.effectiveTo) + "</td></tr>";
  }

  var rowRenderers = {
    organization: organizationRow,
    insurer: insurerRow,
    product: productRow,
    agent: agentRow,
    "commission-item": commissionItemRow
  };

  function pageButton(label, targetPage, options, key) {
    var button = document.createElement(options.active ? "span" : "button");
    if (!options.active) button.type = "button";
    button.className = "pagination-button" + (options.active ? " is-active" : "") + (options.disabled ? " is-disabled" : "");
    button.textContent = label;
    if (!options.active) button.disabled = options.disabled;
    if (options.active) button.setAttribute("aria-current", "page");
    if (!options.disabled && !options.active) {
      button.addEventListener("click", function () {
        state.pages[key] = targetPage;
        loadPanel(key);
      });
    }
    return button;
  }

  function renderPagination(key, page, totalPages) {
    var pagination = panelElement(key, "pagination");
    pagination.replaceChildren();
    if (!totalPages) return;
    pagination.appendChild(pageButton("‹", page - 1, { active: false, disabled: page <= 1 }, key));
    var start = Math.max(1, page - 2);
    var end = Math.min(totalPages, start + 4);
    start = Math.max(1, end - 4);
    for (var current = start; current <= end; current += 1) {
      pagination.appendChild(pageButton(String(current), current, { active: current === page, disabled: false }, key));
    }
    pagination.appendChild(pageButton("›", page + 1, { active: false, disabled: page >= totalPages }, key));
  }

  function renderPage(key, data) {
    var isCommissionItem = key === "commission-item";
    var content = isCommissionItem
      ? (Array.isArray(data) ? data : [])
      : (data && Array.isArray(data.content) ? data.content : []);
    var message = panelElement(key, "message");
    var table = panelElement(key, "table");
    var totalElements = isCommissionItem ? content.length : (data && data.totalElements || 0);
    panelElement(key, "count").textContent = String(totalElements);
    if (!content.length) {
      setMessage(key, "조건에 맞는 기준정보가 없습니다.", false);
      table.hidden = true;
    } else {
      panelElement(key, "body").innerHTML = content.map(rowRenderers[key]).join("");
      message.hidden = true;
      table.hidden = false;
      scheduleDisclosureSync(panelElement(key, "panel"));
    }
    if (!isCommissionItem) {
      state.pages[key] = data && data.page || state.pages[key];
      renderPagination(key, state.pages[key], data && data.totalPages || 0);
    }
    root.querySelector('[data-base-panel="' + key + '"]').setAttribute("aria-busy", "false");
  }

  function writeLocation(key) {
    var query = new URLSearchParams();
    var current = new URLSearchParams(window.location.search);
    if (current.get("month")) query.set("month", current.get("month"));
    query.set("tab", key);
    var params = formParams(key);
    Object.keys(params).forEach(function (name) {
      if (name !== "size" && params[name] !== "") query.set(name, params[name]);
    });
    window.history.replaceState(null, "", window.location.pathname + "?" + query.toString());
  }

  function loadPanel(key) {
    var params = formParams(key);
    if (key === "product" && !params.insurerId) {
      panelElement(key, "count").textContent = "0";
      panelElement(key, "table").hidden = true;
      clearPagination(key);
      setMessage(key, "보험회사를 선택하면 기준일에 판매 가능한 상품을 조회합니다.", false);
      writeLocation(key);
      return;
    }
    if (state.requests[key]) state.requests[key].abort();
    state.requests[key] = new AbortController();
    setLoading(key);
    writeLocation(key);
    var method = {
      organization: baseApi.getOrganizations,
      insurer: baseApi.getInsurers,
      product: baseApi.getProducts,
      agent: baseApi.getAgents,
      "commission-item": baseApi.getCommissionItems
    }[key];
    method(params, { signal: state.requests[key].signal })
      .then(function (envelope) {
        renderPage(key, envelope.data);
        state.loaded[key] = true;
      })
      .catch(function (error) {
        if (error && error.name === "AbortError") return;
        setError(key, error);
      });
  }

  function collectOptions(method, params, page, items) {
    var requestParams = Object.assign({}, params, { page: page, size: OPTION_PAGE_SIZE });
    return method(requestParams).then(function (envelope) {
      var data = envelope.data || {};
      var combined = items.concat(data.content || []);
      return page < (data.totalPages || 0) ? collectOptions(method, params, page + 1, combined) : combined;
    });
  }

  function replaceOptions(select, placeholder, items, valueKey, label, selectedValue) {
    select.replaceChildren(new Option(placeholder, ""));
    items.forEach(function (item) {
      select.appendChild(new Option(label(item), item[valueKey]));
    });
    if (selectedValue && Array.from(select.options).some(function (option) { return option.value === selectedValue; })) {
      select.value = selectedValue;
    }
  }

  function ensureInsurerOptions() {
    if (state.insurerOptionsLoaded) return Promise.resolve();
    var select = formFor("product").elements.insurerId;
    var selectedValue = select.value || new URLSearchParams(window.location.search).get(select.name) || "";
    select.disabled = true;
    return collectOptions(baseApi.getInsurers, {}, 1, [])
      .then(function (items) {
        replaceOptions(select, "보험회사를 선택하세요", items, "insurerId", function (item) {
          return item.insurerName + " (" + item.insurerCode + ")" + (item.activeYn ? "" : " · 사용중지");
        }, selectedValue);
        state.insurerOptionsLoaded = true;
      })
      .catch(function (error) {
        setError("product", error);
        throw error;
      })
      .finally(function () { select.disabled = false; });
  }

  function ensureOrganizationOptions(force) {
    var form = formFor("agent");
    var select = form.elements.organizationId;
    var asOf = form.elements.asOf.value;
    if (!force && state.organizationOptionsAsOf === asOf) return Promise.resolve(true);
    var requestId = ++state.organizationOptionsRequestId;
    var selectedValue = state.organizationOptionsInitialized
      ? select.value
      : (select.value || new URLSearchParams(window.location.search).get(select.name) || "");
    select.disabled = true;
    return collectOptions(baseApi.getOrganizations, { asOf: asOf }, 1, [])
      .then(function (items) {
        if (requestId !== state.organizationOptionsRequestId) return false;
        replaceOptions(select, "전체 조직", items, "organizationId", function (item) {
          return item.organizationName + " (" + item.organizationCode + ")" + (item.activeYn ? "" : " · 사용중지");
        }, selectedValue);
        state.organizationOptionsAsOf = asOf;
        state.organizationOptionsInitialized = true;
        return true;
      })
      .catch(function (error) {
        if (requestId !== state.organizationOptionsRequestId) return false;
        setError("agent", error);
        return false;
      })
      .finally(function () {
        if (requestId === state.organizationOptionsRequestId) select.disabled = false;
      });
  }

  function prepareAndLoad(key) {
    if (key === "product") {
      return ensureInsurerOptions()
        .then(function () { loadPanel(key); })
        .catch(function () { /* 옵션 조회 오류는 ensureInsurerOptions에서 표시한다. */ });
    }
    if (key === "agent") return ensureOrganizationOptions(false).then(function (ready) {
      if (ready) loadPanel(key);
    });
    loadPanel(key);
    return Promise.resolve();
  }

  function activateTab(key, shouldLoad) {
    state.activeTab = TAB_KEYS.includes(key) ? key : "organization";
    tabs.forEach(function (tab) {
      var isActive = tab.dataset.baseTab === state.activeTab;
      tab.setAttribute("aria-selected", String(isActive));
      tab.tabIndex = isActive ? 0 : -1;
    });
    panels.forEach(function (panel) { panel.hidden = panel.dataset.basePanel !== state.activeTab; });
    if (shouldLoad && !state.loaded[state.activeTab]) prepareAndLoad(state.activeTab);
    else writeLocation(state.activeTab);
    scheduleDisclosureSync(panelElement(state.activeTab, "panel"));
  }

  function applyInitialQuery() {
    var query = new URLSearchParams(window.location.search);
    var requestedTab = query.get("tab");
    state.activeTab = TAB_KEYS.includes(requestedTab) ? requestedTab : "organization";
    var form = formFor(state.activeTab);
    if (form) {
      Array.from(form.elements).forEach(function (control) {
        if (control.name && query.has(control.name)) control.value = query.get(control.name);
      });
      state.pages[state.activeTab] = Math.max(1, Number(query.get("page")) || 1);
    }
  }

  root.querySelectorAll('input[name="asOf"]').forEach(function (input) { input.value = initialAsOf; });
  applyInitialQuery();

  tabs.forEach(function (tab) {
    tab.addEventListener("click", function () { activateTab(tab.dataset.baseTab, true); });
    tab.addEventListener("keydown", function (event) {
      var currentIndex = tabs.indexOf(tab);
      var nextIndex;
      if (event.key === "ArrowRight") nextIndex = (currentIndex + 1) % tabs.length;
      else if (event.key === "ArrowLeft") nextIndex = (currentIndex - 1 + tabs.length) % tabs.length;
      else if (event.key === "Home") nextIndex = 0;
      else if (event.key === "End") nextIndex = tabs.length - 1;
      else return;
      event.preventDefault();
      tabs[nextIndex].focus();
      activateTab(tabs[nextIndex].dataset.baseTab, true);
    });
  });

  root.querySelectorAll("[data-base-form]").forEach(function (form) {
    var key = form.dataset.baseForm;
    form.addEventListener("submit", function (event) {
      event.preventDefault();
      state.pages[key] = 1;
      if (key === "agent") ensureOrganizationOptions(true).then(function (ready) {
        if (ready) loadPanel(key);
      });
      else loadPanel(key);
    });
  });

  root.querySelectorAll("[data-base-reset]").forEach(function (button) {
    button.addEventListener("click", function () {
      var key = button.dataset.baseReset;
      var form = formFor(key);
      form.reset();
      var asOf = form.elements.asOf;
      if (asOf) asOf.value = initialAsOf;
      state.pages[key] = 1;
      if (key === "agent") ensureOrganizationOptions(true).then(function (ready) {
        if (ready) loadPanel(key);
      });
      else loadPanel(key);
    });
  });

  activateTab(state.activeTab, true);

  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(function () {
      syncTableCellDisclosures(panelElement(state.activeTab, "panel"));
    });
  }

  var disclosureResizeFrame = null;
  window.addEventListener("resize", function () {
    if (disclosureResizeFrame != null) window.cancelAnimationFrame(disclosureResizeFrame);
    disclosureResizeFrame = window.requestAnimationFrame(function () {
      disclosureResizeFrame = null;
      syncTableCellDisclosures(panelElement(state.activeTab, "panel"));
    });
  });
})();
