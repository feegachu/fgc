(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  if (!apiClient) return;

  function queryString(params) {
    var query = new URLSearchParams();
    Object.keys(params || {}).forEach(function (key) {
      var value = params[key];
      if (value !== null && value !== undefined && String(value).trim() !== "") {
        query.set(key, String(value).trim());
      }
    });
    return query.toString();
  }

  function getReferenceList(path, params, options) {
    var query = queryString(params);
    return apiClient.request(path + (query ? "?" + query : ""), options || {});
  }

  function getOrganizations(params, options) {
    return getReferenceList("/api/v1/base/organizations", params, options);
  }

  function getInsurers(params, options) {
    return getReferenceList("/api/v1/base/insurers", params, options);
  }

  function getProducts(params, options) {
    return getReferenceList("/api/v1/base/products", params, options);
  }

  function getAgents(params, options) {
    return getReferenceList("/api/v1/base/agents", params, options);
  }

  function getCommissionItems(params, options) {
    return getReferenceList("/api/v1/base/commission-items", params, options);
  }

  window.FgcUi.baseApi = {
    getOrganizations: getOrganizations,
    getInsurers: getInsurers,
    getProducts: getProducts,
    getAgents: getAgents,
    getCommissionItems: getCommissionItems
  };
})();
