(function () {
  "use strict";

  function apiClient() {
    return window.FgcUi && window.FgcUi.apiClient;
  }

  function queryString(parameters) {
    var query = new URLSearchParams();
    Object.keys(parameters).forEach(function (key) {
      var value = parameters[key];
      if (value !== null && value !== undefined && value !== "") query.set(key, value);
    });
    return query.toString();
  }

  function getInsurers() {
    return apiClient().request("/api/v1/base/insurers?" + queryString({ page: 1, size: 100 }));
  }

  function getProductOfferings(insurerId, asOf) {
    return apiClient().request("/api/v1/base/products?" + queryString({
      insurerId: insurerId,
      asOf: asOf,
      page: 1,
      size: 100
    }));
  }

  function getAgents(asOf) {
    return apiClient().request("/api/v1/base/agents?" + queryString({
      asOf: asOf,
      page: 1,
      size: 100
    }));
  }

  function getContract(contractId) {
    return apiClient().request("/api/v1/contracts/" + encodeURIComponent(contractId));
  }

  function createContract(contract) {
    return apiClient().request("/api/v1/contracts", { method: "POST", body: contract });
  }

  function updateContract(contractId, contract) {
    return apiClient().request("/api/v1/contracts/" + encodeURIComponent(contractId), {
      method: "PUT",
      body: contract
    });
  }

  window.FgcUi = window.FgcUi || {};
  window.FgcUi.contractApi = {
    getInsurers: getInsurers,
    getProductOfferings: getProductOfferings,
    getAgents: getAgents,
    getContract: getContract,
    createContract: createContract,
    updateContract: updateContract
  };
})();
