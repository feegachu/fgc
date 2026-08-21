/*
 * FGC-UI-CONT-W01 보험계약 목록 — 기준정보 드롭다운(Ajax)·CSV 내보내기·긴 값 전체 보기.
 * 목록 본문은 MPA 서버 렌더링이다 (인터페이스정의서 5-3).
 */
(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var format = (window.FgcUi && window.FgcUi.format) || null;
  var form = document.getElementById("contract-filter-form");

  upgradeDisclosureCells();
  scheduleDisclosureSync();
  bindExport();

  if (!apiClient || !form) return;

  var insurerSelect = document.getElementById("filter-insurer");
  var productSelect = document.getElementById("filter-product");
  var organizationSelect = document.getElementById("filter-organization");
  var agentSelect = document.getElementById("filter-agent");
  var params = new URLSearchParams(window.location.search);
  var selected = {
    insurerId: params.get("insurerId") || "",
    productOfferingId: params.get("productOfferingId") || "",
    orgId: params.get("orgId") || "",
    agentId: params.get("agentId") || ""
  };

  insurerSelect.addEventListener("change", function () {
    selected.productOfferingId = "";
    loadProducts(insurerSelect.value);
  });
  organizationSelect.addEventListener("change", function () {
    selected.agentId = "";
    loadAgents(organizationSelect.value);
  });

  Promise.all([loadInsurers(), loadOrganizations()]).then(function () {
    return Promise.all([loadProducts(selected.insurerId), loadAgents(selected.orgId)]);
  });

  /* 기준일은 Asia/Seoul 고정 — js/common/format.js 하나만 쓴다 (contract-form.js 와 중복 구현이었다). */
  function todayText() {
    return format ? format.today() : new Date().toISOString().slice(0, 10);
  }

  function toast(message, tone) {
    if (window.FgcUi && typeof window.FgcUi.toast === "function") window.FgcUi.toast(message, tone);
  }

  function errorText(error, fallback) {
    return format ? format.errorText(error, fallback) : ((error && error.message) || fallback);
  }

  function content(envelope) {
    return envelope && envelope.data && Array.isArray(envelope.data.content)
      ? envelope.data.content : [];
  }

  function setOptions(select, rows, valueKey, label, placeholder, selectedValue) {
    select.replaceChildren();
    addOption(select, "", placeholder, false);
    rows.forEach(function (row) {
      addOption(select, row[valueKey], label(row), row.activeYn === false);
    });
    select.value = selectedValue || "";
    setBusy(select, false);
    select.disabled = false;
  }

  function addOption(select, value, label, disabled) {
    var option = document.createElement("option");
    option.value = value == null ? "" : String(value);
    option.textContent = label;
    option.disabled = disabled === true;
    select.appendChild(option);
  }

  /* 로딩 중임을 disabled 만으로 알리지 않는다 — 실패 상태와 시각·낭독이 같아진다. */
  function setBusy(select, busy) {
    select.disabled = busy;
    select.setAttribute("aria-busy", busy ? "true" : "false");
    select.classList.toggle("is-loading", busy);
    select.classList.remove("is-error");
  }

  function loadInsurers() {
    setBusy(insurerSelect, true);
    return apiClient.request("/api/v1/base/insurers?page=1&size=100")
      .then(function (envelope) {
        setOptions(insurerSelect, content(envelope), "insurerId", function (row) {
          return row.insurerCode + " · " + row.insurerName;
        }, "전체", selected.insurerId);
      }).catch(function (error) { showLoadError(insurerSelect, "보험회사", error, loadInsurers); });
  }

  function loadProducts(insurerId) {
    setBusy(productSelect, true);
    if (!insurerId) {
      setOptions(productSelect, [], "productOfferingId", function () { return ""; },
        "보험회사를 선택하세요", "");
      return Promise.resolve();
    }
    return apiClient.request("/api/v1/base/products?insurerId=" + encodeURIComponent(insurerId)
      + "&asOf=" + encodeURIComponent(todayText()) + "&page=1&size=100")
      .then(function (envelope) {
        setOptions(productSelect, content(envelope), "productOfferingId", function (row) {
          return row.productName + " · " + row.offeringVersion;
        }, "전체", selected.productOfferingId);
      }).catch(function (error) {
        showLoadError(productSelect, "상품", error, function () { return loadProducts(insurerId); });
      });
  }

  function loadOrganizations() {
    setBusy(organizationSelect, true);
    return apiClient.request("/api/v1/base/organizations?asOf=" + encodeURIComponent(todayText())
      + "&page=1&size=100")
      .then(function (envelope) {
        setOptions(organizationSelect, content(envelope), "organizationId", function (row) {
          return row.organizationCode + " · " + row.organizationName;
        }, "전체", selected.orgId);
      }).catch(function (error) { showLoadError(organizationSelect, "조직", error, loadOrganizations); });
  }

  function loadAgents(organizationId) {
    setBusy(agentSelect, true);
    var path = "/api/v1/base/agents?asOf=" + encodeURIComponent(todayText()) + "&page=1&size=100";
    if (organizationId) path += "&organizationId=" + encodeURIComponent(organizationId);
    return apiClient.request(path).then(function (envelope) {
      var agents = content(envelope).filter(function (row) {
        return row.activeYn !== false && row.agentStatus === "ACTIVE";
      });
      setOptions(agentSelect, agents, "agentId", function (row) {
        return row.agentCode + " · " + row.agentName;
      }, "전체", selected.agentId);
    }).catch(function (error) {
      showLoadError(agentSelect, "설계사", error, function () { return loadAgents(organizationId); });
    });
  }

  /*
   * 실패는 로딩과 구분되어야 하고 되돌릴 수단이 있어야 한다.
   * 드롭다운은 문구를 바꾸는 것 말고 자리가 없으므로 라벨 옆에 다시 시도 버튼을 붙인다.
   */
  function showLoadError(select, label, error, retry) {
    select.replaceChildren();
    addOption(select, "", label + " 목록을 불러오지 못했습니다.", false);
    select.disabled = true;
    select.setAttribute("aria-busy", "false");
    select.classList.remove("is-loading");
    select.classList.add("is-error");
    addRetryButton(select, label, retry);
    toast(errorText(error, label + " 목록을 불러오지 못했습니다."), "error");
  }

  function addRetryButton(select, label, retry) {
    var field = select.closest(".filter-field");
    if (!field || field.querySelector("[data-filter-retry]")) return;
    var button = document.createElement("button");
    button.type = "button";
    button.className = "button button-ghost contract-filter-retry";
    button.dataset.filterRetry = "";
    button.textContent = "다시 시도";
    button.setAttribute("aria-label", label + " 목록 다시 불러오기");
    button.addEventListener("click", function () {
      button.remove();
      select.classList.remove("is-error");
      retry();
    });
    field.appendChild(button);
  }

  /*
   * CSV 내보내기 — 순수 <a href> 는 성공도 실패도 알려 주지 않는다.
   * 같은 URL 을 fetch 로 받아 Blob 으로 저장하고 결과를 Toast 로 알린다.
   * JS 가 없으면 원래대로 링크 이동이 그대로 동작한다 (progressive enhancement).
   */
  function bindExport() {
    var link = document.getElementById("contract-export-button");
    if (!link || typeof window.fetch !== "function" || typeof URL.createObjectURL !== "function") return;

    link.addEventListener("click", function (event) {
      event.preventDefault();
      if (link.getAttribute("aria-busy") === "true") return;
      link.setAttribute("aria-busy", "true");
      link.classList.add("is-loading");

      window.fetch(link.href, { credentials: "same-origin", headers: { Accept: "text/csv" } })
        .then(function (response) {
          if (!response.ok) return failedExport(response);
          return response.blob().then(function (blob) {
            saveBlob(blob, exportFilename(response));
            toast("CSV 파일을 내려받았습니다.", "success");
          });
        })
        .catch(function (error) { toast(errorText(error, "CSV 내보내기에 실패했습니다."), "error"); })
        .then(function () {
          link.setAttribute("aria-busy", "false");
          link.classList.remove("is-loading");
        });
    });
  }

  /* 실패 응답은 CSV 가 아니라 공통 오류 봉투다 — 오류코드·요청 ID 까지 살려서 알린다. */
  function failedExport(response) {
    return response.json().catch(function () { return null; }).then(function (envelope) {
      var error = (envelope && envelope.error) || {};
      error.requestId = (envelope && envelope.requestId) || response.headers.get("X-Request-Id");
      throw error;
    });
  }

  function saveBlob(blob, filename) {
    var url = URL.createObjectURL(blob);
    var anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    /* 다운로드를 비동기로 시작하는 브라우저가 있다 — 같은 태스크에서 해제하면 저장이 실패한다. */
    window.setTimeout(function () { URL.revokeObjectURL(url); }, 0);
  }

  /*
   * 서버가 Content-Disposition 으로 "보험계약목록_yyyyMMdd_HHmmss.csv" 를 내려준다.
   * 헤더가 없거나 읽지 못할 때만 기본 이름을 쓴다.
   */
  function exportFilename(response) {
    var header = response.headers.get("Content-Disposition") || "";
    var encoded = header.match(/filename\*\s*=\s*UTF-8''([^;]+)/i);
    if (encoded) {
      try { return decodeURIComponent(encoded[1].trim()); } catch (error) { /* 아래 기본값으로 */ }
    }
    /* 남는 filename= 은 RFC 2047 인코딩워드(=?UTF-8?Q?…?=)일 수 있다 — 그대로 쓰면 파일명이 깨진다. */
    var plain = header.match(/filename\s*=\s*"?([^";]+)"?/i);
    if (plain && plain[1].indexOf("=?") === -1) return plain[1].trim();
    return "contracts.csv";
  }

  /*
   * 긴 값 [전체 보기 / 접기].
   * .data-table th,td 가 nowrap + ellipsis 라 잘린 값은 다른 방법으로 볼 수 없다.
   * 서버가 이미 그린 셀을 감싸기만 하므로 문자열을 innerHTML 로 넣는 일이 없다 (escape 문제 없음).
   */
  function upgradeDisclosureCells() {
    document.querySelectorAll("td[data-disclosure]").forEach(function (cell) {
      if (cell.querySelector(".table-cell-disclosure")) return;
      var disclosure = document.createElement("div");
      disclosure.className = "table-cell-disclosure";
      var preview = document.createElement("span");
      preview.className = "table-cell-preview is-single-line";
      var full = document.createElement("p");
      full.className = "table-cell-full";
      Array.from(cell.childNodes).forEach(function (node) {
        preview.appendChild(node.cloneNode(true));
      });
      /* 전체 보기는 값 확인용이다 — 링크까지 복제하면 스크린리더가 같은 목적지를 두 번 읽는다. */
      full.textContent = cell.textContent.trim();

      var details = document.createElement("details");
      details.className = "table-cell-details";
      details.hidden = true;
      var summary = document.createElement("summary");
      summary.append(labelSpan("table-cell-more", "전체 보기"), labelSpan("table-cell-less", "접기"), chevron());
      details.append(summary, full);
      disclosure.append(preview, details);
      cell.replaceChildren(disclosure);
      cell.classList.add("contract-disclosure-cell");
    });
  }

  function labelSpan(className, text) {
    var element = document.createElement("span");
    element.className = className;
    element.textContent = text;
    return element;
  }

  function chevron() {
    var element = document.createElement("span");
    element.className = "material-symbols-rounded table-cell-chevron";
    element.setAttribute("aria-hidden", "true");
    element.textContent = "expand_more";
    return element;
  }

  /* 실제로 잘린 셀에만 컨트롤을 노출한다 (policy-list.js·exception-list.js 와 같은 방식). */
  function syncTableCellDisclosures() {
    document.querySelectorAll(".table-cell-disclosure").forEach(function (disclosure) {
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
    /* 폰트가 바뀌면 폭이 달라진다 — policy-list.js·exception-list.js 와 같이 한 번 더 맞춘다. */
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(syncTableCellDisclosures);
  }

  var resizeFrame = null;
  window.addEventListener("resize", function () {
    if (resizeFrame != null) window.cancelAnimationFrame(resizeFrame);
    resizeFrame = window.requestAnimationFrame(function () {
      resizeFrame = null;
      syncTableCellDisclosures();
    });
  });
})();
