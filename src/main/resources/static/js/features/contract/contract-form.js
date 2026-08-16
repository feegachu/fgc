var insurerSelect = document.getElementById("insurer");
var offeringSelect = document.getElementById("offering");

insurerSelect.addEventListener("change", function () {
    var insurerId = insurerSelect.value;

    offeringSelect.disabled = true;
    offeringSelect.innerHTML =
        '<option value="">상품을 불러오는 중입니다.</option>';

    if (!insurerId) {
        offeringSelect.innerHTML =
            '<option value="">보험회사를 먼저 선택하세요</option>';
        return;
    }

    apiClient
        .request(
            "/api/v1/base/product-offerings?insurerId=" +
            encodeURIComponent(insurerId)
        )
        .then(function (envelope) {
            renderOfferings(envelope.data);
        })
        .catch(function (error) {
            offeringSelect.innerHTML =
                '<option value="">상품을 불러오지 못했습니다.</option>';

            console.error(error);
        });
});

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
            offering.productName + " · " + offering.channelCode;

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
