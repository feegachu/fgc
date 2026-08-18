(function () {
  "use strict";

  var form = document.querySelector("#contract-form");
  if (!form) return;

  var contractApi = window.FgcUi && window.FgcUi.contractApi;
  if (!contractApi) return;

  var elements = {
    insurerId: document.querySelector("#insurer-id"),
    productOfferingId: document.querySelector("#product-offering-id"),
    contractNo: document.querySelector("#contract-no"),
    contractDate: document.querySelector("#contract-date"),
    contractStatus: document.querySelector("#contract-status"),
    agentId: document.querySelector("#agent-id"),
    organizationId: document.querySelector("#organization-id"),
    organizationName: document.querySelector("#organization-name"),
    paymentCycleCode: document.querySelector("#payment-cycle-code"),
    premiumPerCycleAmount: document.querySelector("#premium-per-cycle-amount"),
    firstPremiumAmount: document.querySelector("#first-premium-amount"),
    monthlyEquivalentFirstPremium: document.querySelector("#monthly-equivalent-first-premium"),
    paymentTermMonths: document.querySelector("#payment-term-months"),
    standardSurrenderDeductionAmount: document.querySelector("#standard-surrender-deduction-amount")
  };
  var saveButton = document.querySelector("#contract-save-button");
  var saveHint = document.querySelector("#contract-save-hint");
  var errorSummary = document.querySelector("#contract-form-error-summary");
  var errorMessage = document.querySelector("#contract-form-error-message");
  var requestIdMessage = document.querySelector("#contract-form-request-id");
  var isEditMode = form.dataset.mode === "edit";
  var contractId = form.dataset.contractId || null;
  var isInitializing = true;
  var isLoadingProducts = false;
  var isLoadingAgents = false;
  var isSubmitting = false;
  var productRequestSequence = 0;
  var agentRequestSequence = 0;

  function today() {
    var date = new Date();
    var localDate = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
    return localDate.toISOString().slice(0, 10);
  }

  function pageContent(pageResponse) {
    return pageResponse && Array.isArray(pageResponse.content) ? pageResponse.content : [];
  }

  function replaceOptions(select, placeholder, items, optionFactory) {
    select.replaceChildren();
    var placeholderOption = document.createElement("option");
    placeholderOption.value = "";
    placeholderOption.textContent = placeholder;
    select.appendChild(placeholderOption);
    items.forEach(function (item) { select.appendChild(optionFactory(item)); });
  }

  function option(value, label) {
    var item = document.createElement("option");
    item.value = String(value);
    item.textContent = label;
    return item;
  }

  function ensureOption(select, value, label) {
    if (value === null || value === undefined || value === "") return;
    var stringValue = String(value);
    var exists = Array.from(select.options).some(function (item) { return item.value === stringValue; });
    if (!exists) select.appendChild(option(stringValue, label || stringValue));
    select.value = stringValue;
  }

  function setFieldError(field, message) {
    var fieldContainer = document.querySelector('[data-field="' + field + '"]');
    var fieldError = document.querySelector('[data-field-error="' + field + '"]');
    if (fieldContainer) fieldContainer.classList.toggle("is-error", Boolean(message));
    if (fieldError) fieldError.textContent = message || "";
  }

  function clearFieldError(field) {
    setFieldError(field, "");
  }

  function clearErrors() {
    Object.keys(elements).forEach(clearFieldError);
    errorSummary.hidden = true;
    errorMessage.textContent = "";
    requestIdMessage.hidden = true;
    requestIdMessage.textContent = "";
  }

  function showError(error, fallbackMessage) {
    var message = error && error.message ? error.message : fallbackMessage;
    if (error && error.field && Object.prototype.hasOwnProperty.call(elements, error.field)) {
      setFieldError(error.field, message);
      var target = elements[error.field];
      if (target && target.type !== "hidden") target.focus();
    }
    errorMessage.textContent = message;
    if (error && error.requestId) {
      requestIdMessage.textContent = "요청 ID: " + error.requestId;
      requestIdMessage.hidden = false;
    }
    errorSummary.hidden = false;
    errorSummary.scrollIntoView({ behavior: "smooth", block: "center" });
  }

  function setOrganizationFromAgent() {
    var selected = elements.agentId.selectedOptions[0];
    elements.organizationId.value = selected ? selected.dataset.organizationId || "" : "";
    elements.organizationName.value = selected ? selected.dataset.organizationName || "" : "";
    clearFieldError("agentId");
    clearFieldError("organizationId");
    updateSaveState();
  }

  function updateSaveState() {
    var requiredElements = [
      elements.insurerId,
      elements.productOfferingId,
      elements.contractNo,
      elements.contractDate,
      elements.contractStatus,
      elements.agentId,
      elements.paymentCycleCode,
      elements.premiumPerCycleAmount,
      elements.firstPremiumAmount,
      elements.monthlyEquivalentFirstPremium,
      elements.paymentTermMonths
    ];
    var hasRequiredValues = requiredElements.every(function (element) {
      return element && element.value !== "" && element.checkValidity();
    }) && elements.organizationId.value !== "" && form.checkValidity();
    var isBusy = isInitializing || isLoadingProducts || isLoadingAgents || isSubmitting;
    saveButton.disabled = !hasRequiredValues || isBusy;

    if (isSubmitting) saveHint.textContent = "계약과 스케줄을 저장하고 있습니다.";
    else if (isInitializing || isLoadingProducts || isLoadingAgents) saveHint.textContent = "기준정보를 불러오는 중입니다.";
    else if (hasRequiredValues) saveHint.textContent = "저장할 준비가 되었습니다.";
    else saveHint.textContent = "필수 항목(*)과 자동 입력되는 소속 조직을 확인해 주세요.";
  }

  function loadInsurers(selectedId, fallbackLabel) {
    elements.insurerId.disabled = true;
    return contractApi.getInsurers().then(function (envelope) {
      var insurers = pageContent(envelope.data);
      replaceOptions(elements.insurerId, "보험회사를 선택하세요", insurers, function (insurer) {
        var item = option(insurer.insurerId, insurer.insurerName + " (" + insurer.insurerCode + ")");
        item.disabled = insurer.activeYn === false && String(insurer.insurerId) !== String(selectedId || "");
        return item;
      });
      ensureOption(elements.insurerId, selectedId, fallbackLabel);
      elements.insurerId.disabled = false;
    });
  }

  function loadProducts(selectedId, fallbackLabel) {
    var insurerId = elements.insurerId.value;
    var contractDate = elements.contractDate.value;
    var requestSequence = ++productRequestSequence;
    elements.productOfferingId.value = "";
    elements.productOfferingId.disabled = true;

    if (!insurerId || !contractDate) {
      replaceOptions(elements.productOfferingId, "보험회사와 계약일을 먼저 선택하세요", [], option);
      isLoadingProducts = false;
      updateSaveState();
      return Promise.resolve();
    }

    isLoadingProducts = true;
    replaceOptions(elements.productOfferingId, "상품 판매버전을 불러오는 중...", [], option);
    updateSaveState();
    return contractApi.getProductOfferings(insurerId, contractDate).then(function (envelope) {
      if (requestSequence !== productRequestSequence) return;
      var products = pageContent(envelope.data);
      replaceOptions(elements.productOfferingId,
        products.length ? "상품 판매버전을 선택하세요" : "판매 가능한 상품이 없습니다.",
        products,
        function (product) {
          return option(product.productOfferingId, product.productName + " · " + product.offeringVersion);
        });
      ensureOption(elements.productOfferingId, selectedId, fallbackLabel);
      elements.productOfferingId.disabled = products.length === 0 && !selectedId;
    }).catch(function (error) {
      if (requestSequence !== productRequestSequence) return;
      replaceOptions(elements.productOfferingId, "상품을 불러오지 못했습니다.", [], option);
      showError(error, "상품 판매버전을 불러오지 못했습니다.");
    }).finally(function () {
      if (requestSequence === productRequestSequence) {
        isLoadingProducts = false;
        updateSaveState();
      }
    });
  }

  function loadAgents(selectedId, fallbackAgent) {
    var contractDate = elements.contractDate.value;
    var requestSequence = ++agentRequestSequence;
    elements.agentId.value = "";
    elements.organizationId.value = "";
    elements.organizationName.value = "";
    elements.agentId.disabled = true;

    if (!contractDate) {
      replaceOptions(elements.agentId, "계약일을 먼저 선택하세요", [], option);
      isLoadingAgents = false;
      updateSaveState();
      return Promise.resolve();
    }

    isLoadingAgents = true;
    replaceOptions(elements.agentId, "설계사를 불러오는 중...", [], option);
    updateSaveState();
    return contractApi.getAgents(contractDate).then(function (envelope) {
      if (requestSequence !== agentRequestSequence) return;
      var agents = pageContent(envelope.data);
      replaceOptions(elements.agentId,
        agents.length ? "모집 설계사를 선택하세요" : "선택 가능한 설계사가 없습니다.",
        agents,
        function (agent) {
          var item = option(agent.agentId, agent.agentName + " (" + agent.agentCode + ")");
          item.dataset.organizationId = agent.organizationId;
          item.dataset.organizationName = agent.organizationName;
          return item;
        });
      if (selectedId) {
        ensureOption(elements.agentId, selectedId,
          fallbackAgent ? fallbackAgent.agentName : String(selectedId));
        var selected = elements.agentId.selectedOptions[0];
        if (fallbackAgent && selected) {
          selected.dataset.organizationId = fallbackAgent.organizationId;
          selected.dataset.organizationName = fallbackAgent.organizationName;
        }
        setOrganizationFromAgent();
      }
      elements.agentId.disabled = agents.length === 0 && !selectedId;
    }).catch(function (error) {
      if (requestSequence !== agentRequestSequence) return;
      replaceOptions(elements.agentId, "설계사를 불러오지 못했습니다.", [], option);
      showError(error, "모집 설계사를 불러오지 못했습니다.");
    }).finally(function () {
      if (requestSequence === agentRequestSequence) {
        isLoadingAgents = false;
        updateSaveState();
      }
    });
  }

  function fillContract(contract) {
    elements.contractNo.value = contract.contractNo || "";
    elements.contractDate.value = contract.contractDate || "";
    elements.contractStatus.value = contract.contractStatus || "ACTIVE";
    elements.paymentCycleCode.value = contract.paymentCycleCode || "MONTHLY";
    elements.premiumPerCycleAmount.value = contract.premiumPerCycleAmount ?? "";
    elements.firstPremiumAmount.value = contract.firstPremiumAmount ?? "";
    elements.monthlyEquivalentFirstPremium.value = contract.monthlyEquivalentFirstPremium ?? "";
    elements.paymentTermMonths.value = contract.paymentTermMonths ?? "";
    elements.standardSurrenderDeductionAmount.value = contract.standardSurrenderDeductionAmount ?? "";
  }

  function initializeCreateForm() {
    elements.contractDate.value = today();
    return Promise.all([loadInsurers(), loadAgents()]);
  }

  function initializeEditForm() {
    if (!contractId) return Promise.reject(new Error("수정할 계약 ID가 없습니다."));
    return contractApi.getContract(contractId).then(function (envelope) {
      var contract = envelope.data;
      fillContract(contract);
      return loadInsurers(contract.insurerId, contract.insurerName).then(function () {
        return Promise.all([
          loadProducts(contract.productOfferingId,
            contract.productName + (contract.offeringVersion ? " · " + contract.offeringVersion : "")),
          loadAgents(contract.agentId, {
            agentName: contract.agentName,
            organizationId: contract.organizationId,
            organizationName: contract.organizationName
          })
        ]);
      });
    });
  }

  function numberValue(element) {
    return element.value === "" ? null : Number(element.value);
  }

  function requestBody() {
    return {
      insurerId: Number(elements.insurerId.value),
      productOfferingId: Number(elements.productOfferingId.value),
      contractNo: elements.contractNo.value.trim(),
      contractDate: elements.contractDate.value,
      contractStatus: elements.contractStatus.value,
      agentId: Number(elements.agentId.value),
      organizationId: Number(elements.organizationId.value),
      paymentCycleCode: elements.paymentCycleCode.value,
      premiumPerCycleAmount: numberValue(elements.premiumPerCycleAmount),
      firstPremiumAmount: numberValue(elements.firstPremiumAmount),
      monthlyEquivalentFirstPremium: numberValue(elements.monthlyEquivalentFirstPremium),
      paymentTermMonths: numberValue(elements.paymentTermMonths),
      standardSurrenderDeductionAmount: numberValue(elements.standardSurrenderDeductionAmount)
    };
  }

  function handleReferenceChange(event) {
    clearFieldError(event.target.name);
    if (event.target === elements.insurerId) loadProducts();
    if (event.target === elements.contractDate) {
      clearFieldError("productOfferingId");
      clearFieldError("agentId");
      clearFieldError("organizationId");
      loadProducts();
      loadAgents();
    }
    if (event.target === elements.agentId) setOrganizationFromAgent();
    updateSaveState();
  }

  function handleInput(event) {
    clearFieldError(event.target.name);
    updateSaveState();
  }

  function handleSubmit(event) {
    event.preventDefault();
    clearErrors();
    updateSaveState();
    if (saveButton.disabled) {
      form.reportValidity();
      showError(null, "필수 항목과 입력 범위를 확인해 주세요.");
      return;
    }

    isSubmitting = true;
    saveButton.classList.add("is-loading");
    updateSaveState();
    var operation = isEditMode
      ? contractApi.updateContract(contractId, requestBody())
      : contractApi.createContract(requestBody());

    operation.then(function (envelope) {
      window.location.assign("/contracts/" + encodeURIComponent(envelope.data.contractId));
    }).catch(function (error) {
      showError(error, "보험계약을 저장하지 못했습니다.");
    }).finally(function () {
      isSubmitting = false;
      saveButton.classList.remove("is-loading");
      updateSaveState();
    });
  }

  elements.contractDate.max = today();
  form.addEventListener("input", handleInput);
  form.addEventListener("change", handleReferenceChange);
  form.addEventListener("submit", handleSubmit);

  var initialization = isEditMode ? initializeEditForm() : initializeCreateForm();
  initialization.catch(function (error) {
    showError(error, "계약 입력 화면을 준비하지 못했습니다.");
  }).finally(function () {
    isInitializing = false;
    updateSaveState();
  });
})();
