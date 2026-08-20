/*
 * FGC-UI-CONT-W03 보험계약 등록·수정 (IF-API-18·19).
 *
 * 오류 표시는 두 갈래로 나눈다 (마이그레이션 가이드 §11).
 *   · 필드 유효성 오류 — 해당 필드 옆 .field-error
 *   · 기준정보 로드 실패 — 우상단 Toast (필드와 무관한 화면 준비 실패다)
 */
(function () {
  "use strict";

  var form = document.querySelector("#contract-form");
  if (!form) return;

  var contractApi = window.FgcUi && window.FgcUi.contractApi;
  if (!contractApi) return;

  var format = (window.FgcUi && window.FgcUi.format) || null;
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
  var premiumPerCycleHelp = document.querySelector("#premium-per-cycle-help");
  var contractLimitPreview = document.querySelector("#contract-limit-preview");
  var contractLimitFormula = document.querySelector("#contract-limit-formula");
  var errorSummary = document.querySelector("#contract-form-error-summary");
  var errorMessage = document.querySelector("#contract-form-error-message");
  var requestIdMessage = document.querySelector("#contract-form-request-id");
  var isEditMode = form.dataset.mode === "edit";
  var contractId = form.dataset.contractId || null;
  var isInitializing = true;
  var isLoadingProducts = false;
  var isLoadingAgents = false;
  var isSubmitting = false;
  var productOfferings = [];
  /* 주기 보험료에 사용자 입력 또는 서버 저장값이 들어와 있으면 파생값으로 덮어쓰지 않는다. */
  var isPremiumPerCycleUserValue = false;
  var productRequestSequence = 0;
  var agentRequestSequence = 0;
  var PAYMENT_CYCLE_MONTHS = {
    MONTHLY: 1,
    QUARTERLY: 3,
    SEMI_ANNUAL: 6,
    ANNUAL: 12
  };

  /* Asia/Seoul 고정. contract-list.js 와 바이트 단위로 같던 중복 구현을 공통 유틸로 합쳤다. */
  function today() {
    return format ? format.today() : new Date().toISOString().slice(0, 10);
  }

  function toast(message, tone, duration) {
    if (window.FgcUi && typeof window.FgcUi.toast === "function") window.FgcUi.toast(message, tone, duration);
  }

  /* 기준정보 로드 실패는 필드 오류가 아니다 — 오류코드·요청 ID 를 붙여 Toast 로만 알린다. */
  function showLoadFailure(error, fallbackMessage) {
    toast(format ? format.errorText(error, fallbackMessage)
      : ((error && error.message) || fallbackMessage), "error");
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
    // 개발/운영 API가 제공하는 업무 상세 사유가 있으면 공통 오류코드보다 우선한다.
    // 예: 조직 계층에서 팀장 수령자를 찾지 못해 스케줄 생성이 막힌 경우
    // "입력값을 확인하세요. ({field})"만으로는 사용자가 조치할 수 없다.
    var message = error && error.detail ? error.detail : (error && error.message ? error.message : fallbackMessage);
    if (error && error.code === "FGC-CONT-001") message = "저장 불가 — 이미 등록된 계약번호입니다.";
    if (error && error.field && Object.prototype.hasOwnProperty.call(elements, error.field)) {
      setFieldError(error.field, message);
      var target = elements[error.field];
      if (target && target.type !== "hidden") target.focus();
    }
    errorMessage.textContent = message;
    /* FGC-SIR-007 — 오류코드와 추적ID 를 함께 보여 준다. Toast 와 같은 순서·형식으로 적는다. */
    var trace = [];
    if (error && error.code) trace.push(error.code);
    if (error && error.requestId && message.indexOf(error.requestId) === -1) {
      trace.push("요청 ID: " + error.requestId);
    }
    requestIdMessage.textContent = trace.join(" · ");
    requestIdMessage.hidden = trace.length === 0;
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

  /*
   * 주기 보험료는 사용자가 입력하는 값이다 — 자동 계산은 "비어 있을 때 채워 주는" 것까지만 한다.
   *
   * 화면정의서 :550 이 못박고 있다 —
   *   "premium_per_cycle_amount(원래 주기 보험료)와 monthly_equivalent_first_premium
   *    (월납으로 환산한 값)은 다른 값입니다. ... 한도 계산에 쓰이는 게 이 값입니다"(월납환산).
   * 항목표(:619)도 "주기 보험료 | 입력 | 금액 | O | 0 이상" 으로 입력 필드다.
   * 할인·부가보험료 때문에 실제 청구액은 월납환산 × 개월과 다를 수 있고,
   * 1,200% 한도 기준은 주기 보험료가 아니라 월납환산이므로 파생시킬 이유도 없다.
   *
   * 예전 구현은 일시납 외에는 readOnly + 강제 덮어쓰기였는데, 그 탓에 수정 화면에서
   * fillContract() 가 불러온 저장값을 곧바로 파생값으로 지워 버리고 있었다 (#283 리뷰).
   * 그래서 사용자가 손댔거나 서버 값이 들어온 필드는 다시 계산하지 않는다.
   */
  function syncPremiumPerCycleAmount() {
    var paymentCycle = elements.paymentCycleCode.value;
    var isSinglePayment = paymentCycle === "SINGLE";

    if (premiumPerCycleHelp) {
      premiumPerCycleHelp.textContent = isSinglePayment
        ? "일시납은 한 번 낼 실제 보험료를 입력합니다."
        : "비워 두면 월납환산 초회보험료 × 납입주기로 채웁니다. 실제 청구액이 다르면 직접 고치세요.";
    }

    var cycleMonths = PAYMENT_CYCLE_MONTHS[paymentCycle];
    var monthlyEquivalent = numberValue(elements.monthlyEquivalentFirstPremium);
    if (!isPremiumPerCycleUserValue && cycleMonths && monthlyEquivalent !== null) {
      elements.premiumPerCycleAmount.value = String(monthlyEquivalent * cycleMonths);
    }
    updateCapLimitPreview();
  }

  function updateCapLimitPreview() {
    if (!contractLimitPreview || !contractLimitFormula) return;
    var monthlyEquivalent = numberValue(elements.monthlyEquivalentFirstPremium);
    if (monthlyEquivalent === null) {
      contractLimitFormula.textContent = "월납환산 초회보험료 × 12";
      contractLimitPreview.textContent = "—";
      return;
    }
    contractLimitFormula.textContent = int(monthlyEquivalent) + " × 12";
    contractLimitPreview.textContent = int(monthlyEquivalent * 12);
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
      productOfferings = products;
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
      showLoadFailure(error, "상품 판매버전을 불러오지 못했습니다.");
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
      showLoadFailure(error, "모집 설계사를 불러오지 못했습니다.");
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
    isPremiumPerCycleUserValue = contract.premiumPerCycleAmount != null;
    elements.firstPremiumAmount.value = contract.firstPremiumAmount ?? "";
    elements.monthlyEquivalentFirstPremium.value = contract.monthlyEquivalentFirstPremium ?? "";
    elements.paymentTermMonths.value = contract.paymentTermMonths ?? "";
    elements.standardSurrenderDeductionAmount.value = contract.standardSurrenderDeductionAmount ?? "";
    syncPremiumPerCycleAmount();
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

  function int(value) {
    return format ? format.int(value) : Number(value).toLocaleString("ko-KR");
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
    if (event.target === elements.paymentCycleCode) syncPremiumPerCycleAmount();
    updateSaveState();
  }

  function selectedProductOffering() {
    var selectedId = String(elements.productOfferingId.value || "");
    return productOfferings.find(function (product) {
      return String(product.productOfferingId) === selectedId;
    });
  }

  function warnIfRefundRateTableIsMissing(contractId) {
    var product = selectedProductOffering();
    if (!product || !product.standardDeduction80Yn) return Promise.resolve(false);

    return contractApi.getCapChecks(contractId).then(function (envelope) {
      var checks = Array.isArray(envelope.data) ? envelope.data : [];
      var missingRefundRateTable = checks.some(function (check) {
        var result = check && check.result;
        return result
          && result.resultStatus === "REVIEW_REQUIRED"
          && !result.refundRateTableId
          && result.calculationSnapshot
          && result.calculationSnapshot.refundAdditionCondition === "STANDARD_DEDUCTION_80";
      });
      if (!missingRefundRateTable) return false;

      var term = elements.paymentTermMonths.value || "입력한";
      if (window.FgcUi && typeof window.FgcUi.toast === "function") {
        window.FgcUi.toast(
          term + "개월 납입기간에 적용할 12차월 환급률표가 없어 1,200% 한도 판정이 검토필요입니다. 기준정보를 확인하세요.",
          "warning",
          6000
        );
      }
      return true;
    }).catch(function () {
      // 계약 저장은 성공했으므로 안내 조회 실패가 상세 이동을 막으면 안 된다.
      return false;
    });
  }

  function handleInput(event) {
    clearFieldError(event.target.name);
    if (event.target === elements.premiumPerCycleAmount) {
      /* 비우면 다시 자동 채움 대상으로 돌아간다. */
      isPremiumPerCycleUserValue = event.target.value !== "";
    }
    if (event.target === elements.monthlyEquivalentFirstPremium) syncPremiumPerCycleAmount();
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
    saveButton.setAttribute("aria-busy", "true");
    updateSaveState();
    var operation = isEditMode
      ? contractApi.updateContract(contractId, requestBody())
      : contractApi.createContract(requestBody());

    operation.then(function (envelope) {
      var saved = envelope.data || {};
      var savedContractId = saved.contractId;
      var redirect = function () {
        window.location.assign("/contracts/" + encodeURIComponent(savedContractId));
      };
      /*
       * 저장 성공 Toast 는 여기서 띄우지 않는다 — 곧바로 상세로 이동하므로 화면 전환에 묻힌다.
       * 문구만 남겨 두고 contract-detail.js 가 상세 진입 직후 한 번 꺼내 띄운다 (가이드 §11 마지막 절).
       * IF-API-18·19 가 내려 주는 scheduleHeaderIds 로 "스케줄이 함께 만들어졌다"까지 알린다.
       */
      rememberSaveMessage(saveMessage(saved));
      if (isEditMode) {
        redirect();
        return;
      }
      warnIfRefundRateTableIsMissing(savedContractId).then(function (warned) {
        window.setTimeout(redirect, warned ? 1800 : 0);
      });
    }).catch(function (error) {
      showError(error, "보험계약을 저장하지 못했습니다.");
    }).finally(function () {
      isSubmitting = false;
      saveButton.classList.remove("is-loading");
      saveButton.setAttribute("aria-busy", "false");
      updateSaveState();
    });
  }

  function saveMessage(saved) {
    var scheduleIds = Array.isArray(saved.scheduleHeaderIds) ? saved.scheduleHeaderIds : [];
    var action = isEditMode ? "수정했습니다" : "저장했습니다";
    if (!scheduleIds.length) return "보험계약을 " + action + ".";
    return "보험계약을 " + action + ". 예상 스케줄 " + scheduleIds.length
      + "건을 함께 " + (isEditMode ? "재생성" : "생성") + "했습니다 — [예상 스케줄] 탭에서 확인하세요.";
  }

  function rememberSaveMessage(message) {
    try {
      window.sessionStorage.setItem("fgc.contract.saveMessage", message);
    } catch (error) {
      /* 저장 자체는 성공했다. 안내를 남기지 못해도 이동을 막지 않는다. */
    }
  }

  elements.contractDate.max = today();
  form.addEventListener("input", handleInput);
  form.addEventListener("change", handleReferenceChange);
  form.addEventListener("submit", handleSubmit);

  var initialization = isEditMode ? initializeEditForm() : initializeCreateForm();
  initialization.catch(function (error) {
    showLoadFailure(error, "계약 입력 화면을 준비하지 못했습니다.");
  }).finally(function () {
    isInitializing = false;
    syncPremiumPerCycleAmount();
    updateSaveState();
  });
})();
