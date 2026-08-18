(function () {
  "use strict";

  function escapeHtml(value) {
    return String(value == null ? "" : value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
  }

  function dash(value) {
    return value == null || value === "" ? "-" : escapeHtml(value);
  }

  function formatNumber(value, options) {
    if (value == null || value === "" || (typeof value === "string" && value.trim() === "")) return "-";
    var parsed = Number(value);
    if (!isFinite(parsed)) return "-";
    return escapeHtml(parsed.toLocaleString("ko-KR", options));
  }

  function formatRate(value) {
    if (value == null || value === "" || (typeof value === "string" && value.trim() === "")) return "-";

    var normalized = String(value).trim();
    if (!/^[+-]?\d+(?:\.\d+)?$/.test(normalized)) return "-";

    var sign = "";
    if (normalized.charAt(0) === "-" || normalized.charAt(0) === "+") {
      sign = normalized.charAt(0) === "-" ? "-" : "";
      normalized = normalized.slice(1);
    }

    var parts = normalized.split(".");
    var integerPart = parts[0].replace(/^0+(?=\d)/, "");
    var fractionPart = ((parts[1] || "") + "0000").slice(0, 4);
    var groupedInteger = integerPart.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
    return escapeHtml(sign + groupedInteger + "." + fractionPart);
  }

  function formatRange(from, to) {
    if (formatNumber(from) === "-" && formatNumber(to) === "-") return "-";
    return formatNumber(from, { maximumFractionDigits: 0 })
        + " ~ " + formatNumber(to, { maximumFractionDigits: 0 }) + "회차";
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = { formatNumber: formatNumber, formatRate: formatRate, formatRange: formatRange };
    return;
  }

  var tabs = Array.from(document.querySelectorAll("[data-policy-tab]"));
  if (!tabs.length) return;

  var detailTabs = ["tab-rule", "tab-cap", "tab-refund"];
  var selected = null;
  var detail = null;
  var loadingFor = null;
  var detailAbortController = null;

  var inclusion = {
    INCLUDED: ["산입", "status-badge-success"],
    EXCLUDED: ["제외", "status-badge-neutral"],
    REVIEW_REQUIRED: ["검토 필요", "status-badge-review"]
  };
  var calculationType = { RATE: "요율", FIXED: "정액" };

  function emptyState(message, isError) {
    return "<div class='empty-state policy-empty-state"
        + (isError ? " is-error" : "")
        + "' " + (isError ? "role='alert'" : "role='status'")
        + " data-detail-state>" + escapeHtml(message) + "</div>";
  }

  function tableViewport(tableClass, caption, header, rows) {
    return "<div class='data-table-viewport policy-detail-table-viewport'>"
        + "<table class='data-table policy-detail-table " + tableClass + "'>"
        + "<caption class='visually-hidden'>" + escapeHtml(caption) + "</caption>"
        + header + "<tbody>" + rows + "</tbody></table></div>";
  }

  function inclusionBadge(code) {
    var mapped = inclusion[code] || [code, "status-badge-neutral"];
    return "<span class='status-badge " + mapped[1] + "' title='" + escapeHtml(code) + "'>"
        + escapeHtml(mapped[0]) + "</span>";
  }

  function setDetailState(message, isError) {
    document.querySelectorAll("[data-detail-body]").forEach(function (body) {
      body.innerHTML = emptyState(message, isError);
    });
  }

  function setSelectedCaption() {
    var text = selected ? "선택 정책: " + selected.code + " v" + selected.versionNo : "";
    document.querySelectorAll("[data-selected-policy]").forEach(function (element) {
      element.textContent = text;
    });
  }

  function renderRules(rules) {
    var rows = rules.map(function (rule) {
      return "<tr>"
          + "<td>" + escapeHtml(rule.paymentStageLabel) + "</td>"
          + "<td>" + (rule.insurerName == null ? "전체" : escapeHtml(rule.insurerName)) + "</td>"
          + "<td>" + (rule.productName == null ? "전체" : escapeHtml(rule.productName)) + "</td>"
          + "<td class='tabular-nums'>" + dash(rule.agentRankCode) + "</td>"
          + "<td>" + escapeHtml(rule.itemName) + "</td>"
          + "<td class='tabular-nums'>" + formatRange(rule.installmentFrom, rule.installmentTo) + "</td>"
          + "<td>" + escapeHtml(calculationType[rule.calculationType] || rule.calculationType) + "</td>"
          + "<td class='tabular-nums'>" + escapeHtml(rule.basisCode) + "</td>"
          + "<td class='is-number tabular-nums'>" + formatRate(rule.ratePct) + "</td>"
          + "<td class='is-number tabular-nums'>" + formatNumber(rule.fixedAmount, { maximumFractionDigits: 0 }) + "</td>"
          + "</tr>";
    }).join("");

    if (!rows) return emptyState("이 정책에는 수수료 규칙이 없습니다.", false);

    var header = "<thead><tr>"
        + "<th scope='col'>지급단계</th><th scope='col'>보험사</th><th scope='col'>상품</th><th scope='col'>직급</th><th scope='col'>수수료 항목</th>"
        + "<th scope='col'>회차 구간</th><th scope='col'>계산방식</th><th scope='col'>기준코드</th>"
        + "<th scope='col' class='is-number'>요율(%)</th><th scope='col' class='is-number'>정액(원)</th>"
        + "</tr></thead>";
    return tableViewport("policy-rule-table", "선택 정책의 수수료 규칙", header, rows);
  }

  function renderCapSets(sets) {
    if (!sets.length) return emptyState("이 정책에는 1,200% 룰셋이 없습니다.", false);

    var setRows = sets.map(function (set) {
      return "<tr>"
          + "<td>" + escapeHtml(set.paymentStageLabel) + "</td>"
          + "<td class='tabular-nums'>" + escapeHtml(set.contractDateFrom) + " ~ " + (set.contractDateTo == null ? "계속" : escapeHtml(set.contractDateTo)) + "</td>"
          + "<td class='is-number'>" + escapeHtml(set.firstYearMonths) + "</td>"
          + "<td class='is-number tabular-nums'>" + escapeHtml(set.premiumMultiplier) + "</td>"
          + "<td class='is-number tabular-nums'>" + escapeHtml(set.complianceDeductionPct) + "</td>"
          + "<td class='is-number tabular-nums'>" + escapeHtml(set.warningUsagePct) + "</td>"
          + "<td class='tabular-nums'>" + escapeHtml(set.refundAdditionCondition) + "</td>"
          + "</tr>";
    }).join("");
    var setHeader = "<thead><tr>"
        + "<th scope='col'>지급단계</th><th scope='col'>계약일 범위</th><th scope='col' class='is-number'>초년도 개월</th>"
        + "<th scope='col' class='is-number'>배수</th><th scope='col' class='is-number'>준법경영비 공제율(%)</th>"
        + "<th scope='col' class='is-number'>주의 기준(%)</th><th scope='col'>환급금 가산 조건</th>"
        + "</tr></thead>";
    var result = tableViewport("policy-cap-table", "선택 정책의 1,200% 룰셋", setHeader, setRows);

    result += sets.map(function (set) {
      var itemRows = (set.items || []).map(function (item) {
        return "<tr>"
            + "<td>" + escapeHtml(item.itemName) + "</td>"
            + "<td>" + inclusionBadge(item.inclusionStatus) + "</td>"
            + "<td class='tabular-nums'>" + dash(item.exclusionType) + "</td>"
            + "<td class='tabular-nums'>" + escapeHtml(item.attributionMethod) + "</td>"
            + "<td>" + escapeHtml(item.decisionReason) + "</td>"
            + "</tr>";
      }).join("");
      if (!itemRows) return "";

      var itemHeader = "<thead><tr>"
          + "<th scope='col'>수수료 항목</th><th scope='col'>판정</th><th scope='col'>제외 유형</th>"
          + "<th scope='col'>귀속 방법</th><th scope='col'>판단 이유 (필수 저장값)</th>"
          + "</tr></thead>";
      return "<h3 class='policy-detail-title'>항목별 산입 판정 — " + escapeHtml(set.paymentStageLabel) + "</h3>"
          + tableViewport("policy-cap-item-table", "항목별 산입 판정 " + set.paymentStageLabel, itemHeader, itemRows);
    }).join("");

    return result;
  }

  function renderRefundTables(tables) {
    if (!tables.length) return emptyState("이 정책에는 예상 해약환급률표가 없습니다.", false);

    return tables.map(function (table) {
      var lineRows = (table.lines || []).map(function (line) {
        var mark = line.contractMonthNo === 12 && table.standardDeduction80Yn
            ? "<span class='status-badge status-badge-warning'>1,200% 한도 가산에 쓰는 값</span>"
            : "";
        return "<tr><td class='is-number'>" + escapeHtml(line.contractMonthNo) + "차월</td>"
            + "<td class='is-number tabular-nums'>" + escapeHtml(line.refundRatePct) + "%</td>"
            + "<td>" + mark + "</td></tr>";
      }).join("");

      var lineHeader = "<thead><tr><th scope='col' class='is-number'>차월</th>"
          + "<th scope='col' class='is-number'>예상 해약환급률(%)</th><th scope='col'>비고</th></tr></thead>";
      var title = escapeHtml(table.insurerName) + " · " + escapeHtml(table.productName)
          + " · 납입 " + escapeHtml(table.paymentTermMonths) + "개월 · 채널 " + escapeHtml(table.channelCode);

      return "<h3 class='policy-detail-title'>" + title + "</h3>"
          + "<dl class='policy-refund-metadata'>"
          + "<div><dt>표준해약공제액 80% 이상 공제 대상</dt><dd>" + (table.standardDeduction80Yn ? "예" : "아니오") + "</dd></div>"
          + "<div><dt>적용 기간</dt><dd class='tabular-nums'>" + escapeHtml(table.effectiveFrom) + " ~ " + (table.effectiveTo == null ? "계속" : escapeHtml(table.effectiveTo)) + "</dd></div>"
          + "<div><dt>표 버전(원천 상품코드)</dt><dd class='tabular-nums'>" + escapeHtml(table.sourceProductCode) + "</dd></div>"
          + "<div><dt>원천 문서</dt><dd>" + escapeHtml(table.sourceDocumentRef) + "</dd></div>"
          + "</dl>"
          + tableViewport("policy-refund-table", title + " 예상 해약환급률", lineHeader, lineRows);
    }).join("");
  }

  function renderDetail() {
    var targets = [
      ["#tab-rule [data-detail-body]", renderRules(detail.commissionRules || [])],
      ["#tab-cap [data-detail-body]", renderCapSets(detail.capRuleSets || [])],
      ["#tab-refund [data-detail-body]", renderRefundTables(detail.refundRateTables || [])]
    ];
    targets.forEach(function (target) {
      var body = document.querySelector(target[0]);
      if (body) body.innerHTML = target[1];
    });
  }

  function ensureDetail() {
    if (!selected) {
      setDetailState("정책 버전 탭에서 정책을 선택하세요.", false);
      return;
    }
    if (detail || loadingFor === selected) return;

    var apiClient = window.FgcUi && window.FgcUi.apiClient;
    if (!apiClient) {
      setDetailState("공통 API 클라이언트를 불러오지 못했습니다.", true);
      return;
    }

    var current = selected;
    loadingFor = current;
    detailAbortController = new AbortController();
    var requestController = detailAbortController;
    setDetailState("불러오는 중…", false);
    apiClient.request("/api/v1/policies/" + encodeURIComponent(current.id), {
      signal: requestController.signal
    })
        .then(function (response) {
          if (loadingFor !== current) return;
          detail = response.data || {};
          renderDetail();
        })
        .catch(function (error) {
          if (loadingFor !== current) return;
          if (error && error.name === "AbortError") return;
          var message = error && error.message ? error.message : "정책 상세를 불러오지 못했습니다.";
          setDetailState(message, true);
          if (window.FgcUi && window.FgcUi.toast) window.FgcUi.toast(message, "error");
        })
        .finally(function () {
          if (loadingFor === current) loadingFor = null;
          if (detailAbortController === requestController) detailAbortController = null;
        });
  }

  function selectRow(row) {
    document.querySelectorAll("[data-policy-row]").forEach(function (candidate) {
      var isSelected = candidate === row;
      candidate.classList.toggle("is-selected", isSelected);
      var selector = candidate.querySelector("[data-policy-select]");
      if (selector) selector.checked = isSelected;
    });
    if (detailAbortController) detailAbortController.abort();
    detailAbortController = null;
    loadingFor = null;
    selected = {
      id: row.getAttribute("data-policy-version-id"),
      code: row.getAttribute("data-policy-code"),
      versionNo: row.getAttribute("data-version-no")
    };
    detail = null;
    setSelectedCaption();
    setDetailState("탭을 클릭하면 이 정책의 상세를 불러옵니다.", false);
    var activeTab = tabs.find(function (tab) {
      return tab.getAttribute("aria-selected") === "true";
    });
    if (activeTab && detailTabs.indexOf(activeTab.getAttribute("aria-controls")) >= 0) ensureDetail();
  }

  document.querySelectorAll("[data-policy-row]").forEach(function (row) {
    var selector = row.querySelector("[data-policy-select]");
    row.addEventListener("click", function (event) {
      if (event.target === selector) return;
      selectRow(row);
    });
    if (selector) selector.addEventListener("change", function () {
      if (selector.checked) selectRow(row);
    });
  });

  function activateTab(tab) {
    tabs.forEach(function (candidate) {
      var isActive = candidate === tab;
      candidate.setAttribute("aria-selected", isActive ? "true" : "false");
      candidate.tabIndex = isActive ? 0 : -1;
    });
    document.querySelectorAll("[data-policy-panel]").forEach(function (panel) {
      panel.hidden = panel.id !== tab.getAttribute("aria-controls");
    });
    if (detailTabs.indexOf(tab.getAttribute("aria-controls")) >= 0) ensureDetail();
  }

  tabs.forEach(function (tab) {
    tab.addEventListener("click", function () {
      activateTab(tab);
    });
    tab.addEventListener("keydown", function (event) {
      var nextIndex;
      if (event.key === "ArrowRight") nextIndex = (tabs.indexOf(tab) + 1) % tabs.length;
      else if (event.key === "ArrowLeft") nextIndex = (tabs.indexOf(tab) - 1 + tabs.length) % tabs.length;
      else if (event.key === "Home") nextIndex = 0;
      else if (event.key === "End") nextIndex = tabs.length - 1;
      else return;

      event.preventDefault();
      activateTab(tabs[nextIndex]);
      tabs[nextIndex].focus();
    });
  });

  document.getElementById("as-of").addEventListener("change", function () {
    if (this.value) window.location.assign("/policies?asOf=" + encodeURIComponent(this.value));
  });

  var firstRow = document.querySelector("[data-policy-row]");
  if (firstRow) selectRow(firstRow);
})();
