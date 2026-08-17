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

  function getAllPages(path, parameters) {
    var pageSize = 100;
    function requestPage(page) {
      var query = Object.assign({}, parameters || {}, { page: page, size: pageSize });
      return apiClient().request(path + "?" + queryString(query));
    }
    return requestPage(1).then(function (firstEnvelope) {
      var firstPage = firstEnvelope.data || {};
      var requests = [];
      for (var page = 2; page <= (Number(firstPage.totalPages) || 1); page += 1) {
        requests.push(requestPage(page));
      }
      return Promise.all(requests).then(function (remaining) {
        var content = Array.isArray(firstPage.content) ? firstPage.content.slice() : [];
        remaining.forEach(function (envelope) {
          if (envelope.data && Array.isArray(envelope.data.content)) {
            content = content.concat(envelope.data.content);
          }
        });
        return Object.assign({}, firstEnvelope, {
          data: Object.assign({}, firstPage, { content: content })
        });
      });
    });
  }

  function getInsurers() {
    return getAllPages("/api/v1/base/insurers");
  }

  function getProductOfferings(insurerId, asOf) {
    return getAllPages("/api/v1/base/products", {
      insurerId: insurerId,
      asOf: asOf
    });
  }

  function getAgents(asOf) {
    return getAllPages("/api/v1/base/agents", { asOf: asOf });
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
