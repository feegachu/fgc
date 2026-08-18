(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var form = document.getElementById("contract-filter-form");
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

  function todayText() {
    var now = new Date();
    var local = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
    return local.toISOString().slice(0, 10);
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
    select.disabled = false;
  }

  function addOption(select, value, label, disabled) {
    var option = document.createElement("option");
    option.value = value == null ? "" : String(value);
    option.textContent = label;
    option.disabled = disabled === true;
    select.appendChild(option);
  }

  function loadInsurers() {
    insurerSelect.disabled = true;
    return apiClient.request("/api/v1/base/insurers?page=1&size=100")
      .then(function (envelope) {
        setOptions(insurerSelect, content(envelope), "insurerId", function (row) {
          return row.insurerCode + " · " + row.insurerName;
        }, "전체", selected.insurerId);
      }).catch(function (error) { showLoadError(insurerSelect, "보험회사", error); });
  }

  function loadProducts(insurerId) {
    productSelect.disabled = true;
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
      }).catch(function (error) { showLoadError(productSelect, "상품", error); });
  }

  function loadOrganizations() {
    organizationSelect.disabled = true;
    return apiClient.request("/api/v1/base/organizations?asOf=" + encodeURIComponent(todayText())
      + "&page=1&size=100")
      .then(function (envelope) {
        setOptions(organizationSelect, content(envelope), "organizationId", function (row) {
          return row.organizationCode + " · " + row.organizationName;
        }, "전체", selected.orgId);
      }).catch(function (error) { showLoadError(organizationSelect, "조직", error); });
  }

  function loadAgents(organizationId) {
    agentSelect.disabled = true;
    var path = "/api/v1/base/agents?asOf=" + encodeURIComponent(todayText()) + "&page=1&size=100";
    if (organizationId) path += "&organizationId=" + encodeURIComponent(organizationId);
    return apiClient.request(path).then(function (envelope) {
      var agents = content(envelope).filter(function (row) {
        return row.activeYn !== false && row.agentStatus === "ACTIVE";
      });
      setOptions(agentSelect, agents, "agentId", function (row) {
        return row.agentCode + " · " + row.agentName;
      }, "전체", selected.agentId);
    }).catch(function (error) { showLoadError(agentSelect, "설계사", error); });
  }

  function showLoadError(select, label, error) {
    select.replaceChildren();
    addOption(select, "", label + " 목록을 불러오지 못했습니다.", false);
    select.disabled = true;
    if (window.FgcUi && window.FgcUi.toast) {
      window.FgcUi.toast(error && error.message ? error.message : label + " 조회 실패", "error");
    }
  }
})();
