var insurerSelect = document.getElementById("insurer");
var offeringSelect = document.getElementById("offering");
var agentSelect = document.getElementById("agent");
var organizationInput = document.getElementById("org");
var organizationIdInput = document.getElementById("organizationId");

// TODO: IF-API-05~07 기준정보 API가 구현되면 아래 임시 fixture를 제거한다.
// 값은 로컬 demo DB의 실제 PK이므로 계약 생성 요청의 FK 검증을 통과할 수 있다.
var TEMP_INSURERS = [
    { insurerId: 1, insurerCode: "FGL01", insurerName: "미래가상생명" },
    { insurerId: 2, insurerCode: "FGL02", insurerName: "한빛가상생명" },
    { insurerId: 3, insurerCode: "FGL03", insurerName: "새봄가상생명" },
    { insurerId: 4, insurerCode: "FGL04", insurerName: "온누리가상생명" },
    { insurerId: 5, insurerCode: "FGN01", insurerName: "안전가상손해보험" },
    { insurerId: 6, insurerCode: "FGN02", insurerName: "믿음가상손해보험" }
];

var TEMP_OFFERINGS = [
    { productOfferingId: 1, insurerId: 1, productName: "가상 건강보장보험 A", offeringVersion: "2026-CURRENT-A" },
    { productOfferingId: 2, insurerId: 2, productName: "가상 저해지 건강보험 B", offeringVersion: "2026-CURRENT-B" },
    { productOfferingId: 3, insurerId: 2, productName: "가상 저해지 건강보험 B", offeringVersion: "2026-H1-B" },
    { productOfferingId: 4, insurerId: 3, productName: "가상 경영인정기보험", offeringVersion: "2026-CURRENT-A" },
    { productOfferingId: 5, insurerId: 5, productName: "가상 장기상해보험 A", offeringVersion: "2026-CURRENT-A" },
    { productOfferingId: 6, insurerId: 2, productName: "가상 저해지 건강보험 B", offeringVersion: "2027-FOUR-YEAR" },
    { productOfferingId: 7, insurerId: 3, productName: "가상 경영인정기보험", offeringVersion: "2026-TM-A" },
    { productOfferingId: 8, insurerId: 4, productName: "가상 연금저축보험", offeringVersion: "2026-CURRENT-A" }
];

var TEMP_AGENTS = [
    { agentId: 1, agentCode: "A-DH-001", agentName: "서본부", organizationId: 4, organizationName: "동부본부" },
    { agentId: 2, agentCode: "A-BM-001", agentName: "한지사", organizationId: 6, organizationName: "강동지사" },
    { agentId: 3, agentCode: "A-FC-004", agentName: "최해촉", organizationId: 7, organizationName: "마포1팀" },
    { agentId: 4, agentCode: "A-FC-003", agentName: "박신인", organizationId: 8, organizationName: "강동2팀" },
    { agentId: 5, agentCode: "A-TL-001", agentName: "정팀장", organizationId: 9, organizationName: "강동1팀" },
    { agentId: 6, agentCode: "A-FC-002", agentName: "이보험", organizationId: 9, organizationName: "강동1팀" },
    { agentId: 7, agentCode: "A-FC-001", agentName: "김정산", organizationId: 9, organizationName: "강동1팀" }
];

insurerSelect.addEventListener("change", function () {
    var insurerId = insurerSelect.value;

    offeringSelect.disabled = true;

    if (!insurerId) {
        offeringSelect.innerHTML =
            '<option value="">보험회사를 먼저 선택하세요</option>';
        return;
    }

    renderOfferings(TEMP_OFFERINGS.filter(function (offering) {
        return String(offering.insurerId) === String(insurerId);
    }));
});

agentSelect.addEventListener("change", function () {
    var option = agentSelect.selectedOptions[0];
    organizationInput.value = option ? option.dataset.organizationName || "" : "";
    organizationIdInput.value = option ? option.dataset.organizationId || "" : "";
});

function renderInsurers() {
    insurerSelect.replaceChildren();
    var placeholder = document.createElement("option");
    placeholder.value = "";
    placeholder.textContent = "보험회사를 선택하세요";
    insurerSelect.appendChild(placeholder);

    TEMP_INSURERS.forEach(function (insurer) {
        var option = document.createElement("option");
        option.value = insurer.insurerId;
        option.textContent = insurer.insurerCode + " · " + insurer.insurerName;
        insurerSelect.appendChild(option);
    });
}

function renderAgents() {
    agentSelect.replaceChildren();
    var placeholder = document.createElement("option");
    placeholder.value = "";
    placeholder.textContent = "설계사를 선택하세요";
    agentSelect.appendChild(placeholder);

    TEMP_AGENTS.forEach(function (agent) {
        var option = document.createElement("option");
        option.value = agent.agentId;
        option.textContent = agent.agentCode + " · " + agent.agentName;
        option.dataset.organizationId = agent.organizationId;
        option.dataset.organizationName = agent.organizationName;
        agentSelect.appendChild(option);
    });
}

renderInsurers();
renderAgents();

function renderOfferings(offerings) {
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

var contractForm = document.querySelector("[data-contract-form]");
var saveButton = document.getElementById("save-btn");
var saveHint = document.getElementById("save-hint");
var contractDateInput = document.getElementById("contractDate");
var apiClient = window.FgcUi && window.FgcUi.apiClient;
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
        apiClient.request("/api/v1/contracts", {
            method: "POST",
            body: requestBody()
        }).then(function (envelope) {
            var data = envelope.data || {};
            var scheduleIds = Array.isArray(data.scheduleHeaderIds) ? data.scheduleHeaderIds : [];
            window.location.assign(scheduleIds.length > 0
                ? "/schedules/" + encodeURIComponent(scheduleIds[0])
                : "/contracts/" + encodeURIComponent(data.contractId));
        }).catch(function (error) {
            submitting = false;
            updateSaveButton();
            showError(error);
        });
    });
    updateSaveButton();
}
