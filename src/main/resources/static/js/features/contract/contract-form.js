var insurerSelect = document.getElementById("insurer");
var offeringSelect = document.getElementById("offering");
var agentSelect = document.getElementById("agent");
var organizationInput = document.getElementById("org");
var organizationIdInput = document.getElementById("organizationId");
var apiClient = window.FgcUi && window.FgcUi.apiClient;
var contractForm = document.querySelector("[data-contract-form]");
var formMode = contractForm ? contractForm.dataset.mode : "create";
var editingContractId = contractForm ? contractForm.dataset.contractId : "";
var editingContract = null;

insurerSelect.addEventListener("change", function () {
    var insurerId = insurerSelect.value;

    offeringSelect.disabled = true;

    if (!insurerId) {
        offeringSelect.innerHTML =
            '<option value="">보험회사를 먼저 선택하세요</option>';
        return;
    }

    loadOfferings(insurerId);
});

agentSelect.addEventListener("change", function () {
    var option = agentSelect.selectedOptions[0];
    organizationInput.value = option ? option.dataset.organizationName || "" : "";
    organizationIdInput.value = option ? option.dataset.organizationId || "" : "";
});

function pageContent(envelope) {
    return envelope && envelope.data && Array.isArray(envelope.data.content)
        ? envelope.data.content
        : [];
}

function referenceDate() {
    var value = document.getElementById("contractDate").value;
    return value || todayText();
}

function loadInsurers(selectedId) {
    if (!apiClient) return Promise.resolve();
    insurerSelect.disabled = true;
    return apiClient.request("/api/v1/base/insurers?page=1&size=100")
        .then(function (envelope) { renderInsurers(pageContent(envelope), selectedId); })
        .catch(function (error) { showError(error); });
}

function loadOfferings(insurerId, selectedId) {
    if (!apiClient) return Promise.resolve();
    offeringSelect.disabled = true;
    offeringSelect.innerHTML = '<option value="">상품을 불러오는 중입니다.</option>';
    apiClient.request("/api/v1/base/products?insurerId=" + encodeURIComponent(insurerId)
        + "&asOf=" + encodeURIComponent(referenceDate()) + "&page=1&size=100")
        .then(function (envelope) { renderOfferings(pageContent(envelope), selectedId); })
        .catch(function (error) {
            offeringSelect.innerHTML = '<option value="">상품을 불러오지 못했습니다.</option>';
            showError(error);
        });
}

function loadAgents(selectedId) {
    if (!apiClient) return Promise.resolve();
    agentSelect.disabled = true;
    return apiClient.request("/api/v1/base/agents?asOf=" + encodeURIComponent(referenceDate())
        + "&page=1&size=100")
        .then(function (envelope) { renderAgents(pageContent(envelope), selectedId); })
        .catch(function (error) { showError(error); });
}

function renderInsurers(insurers, selectedId) {
    insurerSelect.replaceChildren();
    var placeholder = document.createElement("option");
    placeholder.value = "";
    placeholder.textContent = "보험회사를 선택하세요";
    insurerSelect.appendChild(placeholder);

    insurers.forEach(function (insurer) {
        var option = document.createElement("option");
        option.value = insurer.insurerId;
        option.textContent = insurer.insurerCode + " · " + insurer.insurerName;
        option.disabled = insurer.activeYn === false;
        option.selected = String(insurer.insurerId) === String(selectedId || "");
        insurerSelect.appendChild(option);
    });
    insurerSelect.disabled = false;
}

function renderAgents(agents, selectedId) {
    agentSelect.replaceChildren();
    var placeholder = document.createElement("option");
    placeholder.value = "";
    placeholder.textContent = "설계사를 선택하세요";
    agentSelect.appendChild(placeholder);

    agents.filter(function (agent) {
        return agent.activeYn !== false && agent.agentStatus === "ACTIVE";
    }).forEach(function (agent) {
        var option = document.createElement("option");
        option.value = agent.agentId;
        option.textContent = agent.agentCode + " · " + agent.agentName;
        option.dataset.organizationId = agent.organizationId;
        option.dataset.organizationName = agent.organizationName;
        option.selected = String(agent.agentId) === String(selectedId || "");
        agentSelect.appendChild(option);
    });
    agentSelect.disabled = false;
    if (selectedId) {
        var selected = agentSelect.selectedOptions[0];
        organizationInput.value = selected ? selected.dataset.organizationName || "" : "";
        organizationIdInput.value = selected ? selected.dataset.organizationId || "" : "";
    }
}

function renderOfferings(offerings, selectedId) {
    offeringSelect.replaceChildren();

    var placeholder = document.createElement("option");
    placeholder.value = "";
    placeholder.textContent = "상품을 선택하세요";
    offeringSelect.appendChild(placeholder);

    offerings.forEach(function (offering) {
        var option = document.createElement("option");

        option.value = offering.productOfferingId;
        option.textContent =
            offering.productName + " · " + offering.offeringVersion;
        option.selected = String(offering.productOfferingId) === String(selectedId || "");

        offeringSelect.appendChild(option);
    });

    offeringSelect.disabled = offerings.length === 0;
}

var paymentCycleSelect = document.getElementById("cycle");
var premiumPerCycleInput = document.getElementById("premiumPerCycle");
var monthlyEquivalentInput = document.getElementById("monthlyEquiv");
var limitFormula = document.getElementById("limit-formula");
var limitPreview = document.getElementById("limit-preview");

var PAYMENT_CYCLE_MONTHS = {
    MONTHLY: 1,
    QUARTERLY: 3,
    SEMI_ANNUAL: 6,
    ANNUAL: 12
};

function parseWon(value) {
    var amount = Number(value);
    return Number.isFinite(amount) && amount >= 0 ? amount : 0;
}

function formatWon(value) {
    return Math.round(value).toLocaleString("ko-KR");
}

function updatePremiums() {
    var monthlyEquivalent = parseWon(monthlyEquivalentInput.value);
    var cycleMonths = PAYMENT_CYCLE_MONTHS[paymentCycleSelect.value];

    // 일시납·기타는 정해진 월 환산 배수가 없으므로 기존 주기 보험료를 유지한다.
    if (cycleMonths) {
        premiumPerCycleInput.value = String(Math.round(monthlyEquivalent * cycleMonths));
    }

    if (limitFormula) {
        limitFormula.textContent = formatWon(monthlyEquivalent) + " × 12";
    }
    if (limitPreview) {
        limitPreview.textContent = formatWon(monthlyEquivalent * 12);
    }
}

if (paymentCycleSelect && premiumPerCycleInput && monthlyEquivalentInput) {
    paymentCycleSelect.addEventListener("change", updatePremiums);
    monthlyEquivalentInput.addEventListener("input", updatePremiums);
    updatePremiums();
}

var saveButton = document.getElementById("save-btn");
var saveHint = document.getElementById("save-hint");
var contractDateInput = document.getElementById("contractDate");
var submitting = false;

function todayText() {
    var now = new Date();
    var local = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
    return local.toISOString().slice(0, 10);
}

function isFormReady() {
    return contractForm.checkValidity() && organizationIdInput.value !== "";
}

function updateSaveButton() {
    var ready = isFormReady();
    saveButton.disabled = submitting || !ready;
    saveHint.textContent = submitting
        ? "계약과 예상 스케줄을 저장하고 있습니다."
        : ready
            ? "저장하면 예상 스케줄이 같은 트랜잭션에서 생성됩니다."
            : "필수 항목(*)을 모두 채우면 저장 버튼이 켜집니다.";
}

function optionalNumber(input) {
    return input.value === "" ? null : Number(input.value);
}

function setValue(id, value) {
    var element = document.getElementById(id);
    if (element) element.value = value == null ? "" : String(value);
}

function populateContract(contract) {
    setValue("contractNo", contract.contractNo);
    setValue("contractDate", contract.contractDate);
    setValue("status", contract.contractStatus);
    setValue("cycle", contract.paymentCycleCode);
    setValue("premiumPerCycle", contract.premiumPerCycleAmount);
    setValue("firstPremium", contract.firstPremiumAmount);
    setValue("monthlyEquiv", contract.monthlyEquivalentFirstPremium);
    setValue("termMonths", contract.paymentTermMonths);
    setValue("stdDeduction", contract.standardSurrenderDeductionAmount);
    organizationIdInput.value = contract.organizationId == null ? "" : String(contract.organizationId);
    updatePremiums();
}

function initializeEditForm() {
    if (formMode !== "edit" || !editingContractId) return Promise.resolve(false);
    return apiClient.request("/api/v1/contracts/" + encodeURIComponent(editingContractId))
        .then(function (envelope) {
            editingContract = envelope.data || {};
            populateContract(editingContract);
            return Promise.all([
                loadInsurers(editingContract.insurerId),
                loadAgents(editingContract.agentId)
            ]);
        })
        .then(function () {
            return loadOfferings(editingContract.insurerId, editingContract.productOfferingId);
        })
        .then(function () {
            updateSaveButton();
            return true;
        });
}

function requestBody() {
    return {
        insurerId: Number(insurerSelect.value),
        contractNo: document.getElementById("contractNo").value.trim(),
        productOfferingId: Number(offeringSelect.value),
        contractDate: contractDateInput.value,
        contractStatus: document.getElementById("status").value,
        agentId: Number(agentSelect.value),
        organizationId: Number(organizationIdInput.value),
        paymentCycleCode: paymentCycleSelect.value,
        firstPremiumAmount: Number(document.getElementById("firstPremium").value),
        monthlyEquivalentFirstPremium: Number(monthlyEquivalentInput.value),
        paymentTermMonths: Number(document.getElementById("termMonths").value),
        standardSurrenderDeductionAmount: optionalNumber(document.getElementById("stdDeduction"))
    };
}

function showError(error) {
    var message = error && error.message ? error.message : "계약을 저장하지 못했습니다.";
    var field = error && (error.field || (error.params && error.params.field));
    if (field) {
        message = "입력값을 확인하세요: " + field;
    } else {
        message = message.replace("({field})", "");
    }
    if (window.FgcUi && window.FgcUi.toast) {
        window.FgcUi.toast(message, "error");
    } else {
        saveHint.textContent = message;
    }
}

if (contractForm && saveButton && saveHint) {
    contractDateInput.max = todayText();
    contractDateInput.addEventListener("change", function () {
        loadAgents();
        if (insurerSelect.value) loadOfferings(insurerSelect.value);
    });
    contractForm.addEventListener("input", updateSaveButton);
    contractForm.addEventListener("change", updateSaveButton);
    contractForm.addEventListener("submit", function (event) {
        event.preventDefault();
        if (!isFormReady()) {
            contractForm.reportValidity();
            updateSaveButton();
            return;
        }
        if (!apiClient || submitting) return;

        submitting = true;
        updateSaveButton();
        var requestUrl = formMode === "edit"
            ? "/api/v1/contracts/" + encodeURIComponent(editingContractId)
            : "/api/v1/contracts";
        apiClient.request(requestUrl, {
            method: formMode === "edit" ? "PUT" : "POST",
            body: requestBody()
        }).then(function (envelope) {
            var data = envelope.data || {};
            var scheduleIds = Array.isArray(data.scheduleHeaderIds) ? data.scheduleHeaderIds : [];
            window.location.assign(formMode === "edit"
                ? "/contracts/" + encodeURIComponent(data.contractId || editingContractId)
                : scheduleIds.length > 0
                    ? "/schedules/" + encodeURIComponent(scheduleIds[0])
                    : "/contracts/" + encodeURIComponent(data.contractId));
        }).catch(function (error) {
            submitting = false;
            updateSaveButton();
            showError(error);
        });
    });
    if (formMode === "edit") {
        initializeEditForm().catch(showError);
    } else {
        loadInsurers();
        loadAgents();
    }
    updateSaveButton();
}
