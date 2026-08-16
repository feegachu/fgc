(function () {
  "use strict";

  var apiClient = window.FgcUi && window.FgcUi.apiClient;
  var main = document.querySelector("[data-transaction-form]");
  if (!apiClient || !main) return;

  var AGENTS = [
    { id: 1, code: "A-DH-001", name: "서본부" },
    { id: 2, code: "A-BM-001", name: "한지사" },
    { id: 3, code: "A-FC-004", name: "최해촉" },
    { id: 4, code: "A-FC-003", name: "박신인" },
    { id: 5, code: "A-TL-001", name: "정팀장" },
    { id: 6, code: "A-FC-002", name: "이보험" },
    { id: 7, code: "A-FC-001", name: "김정산" }
  ];
  var SOURCE_TYPES = [["GA_MANUAL_PAYMENT", "GA 수기지급"]];
  var contracts = [];
  var attributionSequence = 0;
  var saving = false;
  var paymentId = null;

  var stage = document.getElementById("stage");
  var sourceType = document.getElementById("sourceType");
  var bizKey = document.getElementById("bizKey");
  var settlementMonth = document.getElementById("settlementMonth");
  var recipient = document.getElementById("recipient");
  var item = document.getElementById("item");
  var amount = document.getElementById("amount");
  var cashflow = document.getElementById("cashflow");
  var evidence = document.getElementById("evidence");
  var attrBody = document.getElementById("attr-body");
  var addButton = document.getElementById("btn-add-attr");
  var saveButton = document.getElementById("btn-save");
  var precheckButton = document.getElementById("btn-precheck");
  var confirmButton = document.getElementById("btn-confirm");

  fillOptions(sourceType, SOURCE_TYPES, "원천유형을 선택하세요");
  fillOptions(recipient, AGENTS.map(function (agent) {
    return [String(agent.id), agent.code + " " + agent.name];
  }), "수령 설계사를 선택하세요");
  if (!bizKey.value) bizKey.value = generateBusinessKey();

  addButton.addEventListener("click", addAttributionRow);
  saveButton.addEventListener("click", save);
  precheckButton.addEventListener("click", precheck);
  confirmButton.addEventListener("click", confirmPayment);
  amount.addEventListener("input", updateAttributionSummary);
  settlementMonth.addEventListener("change", function () {
    loadCommissionItems();
    attrBody.querySelectorAll("[data-attr-date]").forEach(function (input) {
      if (!input.value) input.value = monthFirstDay();
    });
  });
  cashflow.addEventListener("change", filterCommissionItemsByCashflow);

  loadContracts();
  loadCommissionItems();
  addAttributionRow();

  function loadContracts() {
    apiClient.request("/api/v1/contracts?page=1&size=100")
      .then(function (envelope) {
        contracts = envelope && envelope.data && Array.isArray(envelope.data.content)
          ? envelope.data.content
          : [];
        refreshContractSelects();
      })
      .catch(function (error) { showError(error, "계약 목록을 불러오지 못했습니다."); });
  }

  function loadCommissionItems() {
    var asOf = monthFirstDay();
    if (!asOf) return;
    apiClient.request("/api/v1/base/commission-items?asOf=" + encodeURIComponent(asOf))
      .then(function (envelope) {
        var items = envelope && Array.isArray(envelope.data) ? envelope.data : [];
        item.dataset.items = JSON.stringify(items);
        filterCommissionItemsByCashflow();
      })
      .catch(function (error) { showError(error, "수수료 항목을 불러오지 못했습니다."); });
  }

  function filterCommissionItemsByCashflow() {
    var items = [];
    try { items = JSON.parse(item.dataset.items || "[]"); } catch (ignored) { items = []; }
    var selected = item.value;
    var options = items.filter(function (entry) { return entry.cashflowType === cashflow.value; })
      .map(function (entry) {
        return [String(entry.commissionItemId), entry.itemCode + " · " + entry.itemName];
      });
    fillOptions(item, options, "수수료 항목을 선택하세요");
    item.value = selected;
  }

  function addAttributionRow() {
    if (attrBody.querySelector(".fgc-empty")) clear(attrBody);
    attributionSequence += 1;
    var row = document.createElement("tr");
    row.dataset.attrRow = "";

    appendTextCell(row, attributionSequence);
    appendTextCell(row, "계약");
    row.appendChild(controlCell(contractSelect()));
    appendTextCell(row, selectedAgentLabel());
    row.appendChild(controlCell(input("date", monthFirstDay(), "data-attr-date")));
    row.appendChild(controlCell(readonlyInput(monthFirstDay())));
    row.appendChild(controlCell(input("number", suggestedAmount(), "data-attr-amount")));
    row.appendChild(controlCell(inclusionControls()));
    row.appendChild(controlCell(methodSelect()));
    row.appendChild(controlCell(input("text", "직접 귀속", "data-allocation-basis")));
    row.appendChild(controlCell(input("text", "", "data-attr-evidence")));

    var removeCell = document.createElement("td");
    var removeButton = document.createElement("button");
    removeButton.type = "button";
    removeButton.className = "fgc-btn fgc-btn--ghost";
    removeButton.textContent = "×";
    removeButton.addEventListener("click", function () {
      row.remove();
      renumberRows();
      updateAttributionSummary();
      if (!attrBody.querySelector("[data-attr-row]")) renderEmptyAttributions();
    });
    removeCell.appendChild(removeButton);
    row.appendChild(removeCell);

    row.querySelector("[data-attr-amount]").addEventListener("input", updateAttributionSummary);
    row.querySelector("[data-attr-date]").addEventListener("change", function (event) {
      var monthInput = row.querySelector("[data-attr-month]");
      monthInput.value = event.target.value ? event.target.value.slice(0, 7) + "-01" : "";
    });
    attrBody.appendChild(row);
    updateAttributionSummary();
  }

  function contractSelect() {
    var select = document.createElement("select");
    select.dataset.contractId = "";
    fillOptions(select, contracts.map(function (contract) {
      return [String(contract.contractId), contract.contractNo + " · " + contract.productName];
    }), "계약을 선택하세요");
    return select;
  }

  function refreshContractSelects() {
    attrBody.querySelectorAll("[data-contract-id]").forEach(function (select) {
      var selected = select.value;
      fillOptions(select, contracts.map(function (contract) {
        return [String(contract.contractId), contract.contractNo + " · " + contract.productName];
      }), "계약을 선택하세요");
      select.value = selected;
    });
  }

  function inclusionControls() {
    var wrapper = document.createElement("div");
    var decision = document.createElement("select");
    decision.dataset.inclusion = "";
    fillOptions(decision, [["INCLUDED", "산입"], ["EXCLUDED", "제외"], ["REVIEW_REQUIRED", "검토필요"]]);
    var exclusion = document.createElement("select");
    exclusion.dataset.exclusion = "";
    fillOptions(exclusion, [
      ["NONE", "제외유형 없음"], ["VOICE_RECORDING", "녹취"], ["BROADCAST", "방송"],
      ["NEW_AGENT_SUPPORT", "신인 지원"], ["COMPLIANCE_3PCT", "준법경영비"]
    ]);
    exclusion.hidden = true;
    decision.addEventListener("change", function () {
      exclusion.hidden = decision.value !== "EXCLUDED";
      if (exclusion.hidden) exclusion.value = "NONE";
    });
    wrapper.appendChild(decision);
    wrapper.appendChild(exclusion);
    return wrapper;
  }

  function methodSelect() {
    var select = document.createElement("select");
    select.dataset.attrMethod = "";
    fillOptions(select, [
      ["DIRECT", "직접 귀속"], ["SETTLEMENT_SUPPORT_MONTHLY", "정착지원금 월배부"],
      ["FIRST_CONTRACT_CARRY_FORWARD", "최초계약 이월"], ["APPROVED_ALLOCATION", "승인 배부"],
      ["MANUAL_REVIEW", "수기 검토"], ["NEWCOMER_NON_CONTRACT", "신인 비계약"]
    ]);
    return select;
  }

  function save() {
    if (saving) return;
    var payload;
    try { payload = buildPayload(); } catch (error) { showError(error, error.message); return; }

    saving = true;
    saveButton.disabled = true;
    apiClient.request("/api/v1/transactions", { method: "POST", body: payload })
      .then(function (envelope) {
        paymentId = envelope.data && envelope.data.commissionTransactionId;
        document.getElementById("tx-status").textContent = "작성중 · #" + paymentId;
        lockDraftInputs();
        precheckButton.disabled = false;
        confirmButton.disabled = true;
        toast("작성중(DRAFT)으로 저장했습니다. 저장만 했을 뿐 검증은 하지 않았습니다.", "success", 5000);
      })
      .catch(function (error) { showError(error, "수수료 지급 건을 저장하지 못했습니다."); })
      .finally(function () {
        saving = false;
        if (!paymentId) saveButton.disabled = false;
      });
  }

  function precheck() {
    if (!paymentId) return;
    precheckButton.disabled = true;
    confirmButton.disabled = true;
    renderCapMessage("1,200% 한도를 계산하는 중입니다.");

    apiClient.request("/api/v1/transactions/" + encodeURIComponent(paymentId) + "/precheck", {
      method: "POST"
    }).then(function (envelope) {
      var result = envelope.data;
      renderPrecheck(result);
      confirmButton.disabled = !result.confirmable;
      toast(
        result.confirmable
          ? "사전검증을 통과했습니다. 이제 확정할 수 있습니다."
          : "사전검증에서 확정 차단 사유가 발견되었습니다.",
        result.confirmable ? "success" : "warning",
        5000
      );
    }).catch(function (error) {
      showError(error, "1,200% 사전검증을 수행하지 못했습니다.");
      renderCapMessage("사전검증 중 오류가 발생했습니다.");
    }).finally(function () {
      precheckButton.disabled = false;
    });
  }

  function renderPrecheck(result) {
    var capBody = document.getElementById("cap-body");
    clear(capBody);
    (result.capPreview || []).forEach(function (preview) {
      capBody.appendChild(capPreviewCard(preview));
    });
    if (!result.capPreview || result.capPreview.length === 0) {
      renderCapMessage("표시할 1,200% 검증 결과가 없습니다.");
    }
    renderGates(result.blockers || [], result.confirmable);
  }

  function capPreviewCard(preview) {
    var card = document.createElement("div");
    card.style.cssText = "border:1px solid #d8e0e8;border-top:3px solid #173b57;border-radius:10px;padding:16px;margin-bottom:12px";

    var heading = document.createElement("div");
    heading.style.cssText = "display:flex;justify-content:space-between;gap:12px;font-weight:700";
    heading.appendChild(textElement("span", (preview.paymentStageLabel || preview.paymentStage) + " · " + preview.contractNo));
    var badge = textElement("span", preview.resultStatusLabel + statusSuffix(preview.resultStatus));
    badge.style.cssText = statusStyle(preview.resultStatus);
    heading.appendChild(badge);
    card.appendChild(heading);

    var usage = Math.max(0, number(preview.usagePct));
    var track = document.createElement("div");
    track.style.cssText = "height:10px;background:#e5e7eb;border-radius:999px;margin:14px 0 6px;overflow:hidden";
    var bar = document.createElement("div");
    bar.style.cssText = "height:100%;width:" + Math.min(usage, 100) + "%;background:" + statusColor(preview.resultStatus);
    track.appendChild(bar);
    card.appendChild(track);

    var usageLine = document.createElement("div");
    usageLine.style.cssText = "display:flex;justify-content:space-between;font-size:12px;margin-bottom:12px";
    usageLine.appendChild(textElement("span", "사용률 " + preview.usagePct + "%"));
    usageLine.appendChild(textElement("span", "한도 " + money(preview.limitAmount)));
    card.appendChild(usageLine);

    card.appendChild(amountLine("이번 건 전 산입 누계", preview.existingIncludedAmount));
    card.appendChild(amountLine("이번 건 산입액", preview.candidateAmount));
    card.appendChild(amountLine("합계", preview.includedAmount, true));
    card.appendChild(amountLine("잔여 한도", preview.remainingAmount, preview.remainingAmount < 0));
    return card;
  }

  function amountLine(label, value, emphasize) {
    var line = document.createElement("div");
    line.style.cssText = "display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid #e5e7eb;font-size:13px";
    if (emphasize) line.style.fontWeight = "700";
    line.appendChild(textElement("span", label));
    var amountText = textElement("span", money(value));
    if (number(value) < 0) amountText.style.color = "#ef4444";
    line.appendChild(amountText);
    return line;
  }

  function renderGates(blockers, confirmable) {
    var gateList = document.getElementById("gate-list");
    clear(gateList);
    if (confirmable) {
      var success = textElement("div", "✓ 확정 게이트를 모두 통과했습니다.");
      success.style.cssText = "color:#15803d;font-weight:700;padding:10px 0";
      gateList.appendChild(success);
      return;
    }
    blockers.forEach(function (blocker, index) {
      var row = document.createElement("div");
      row.className = "gate-step gate--fail";
      var no = textElement("span", index + 1);
      no.className = "gate-no";
      row.appendChild(no);
      row.appendChild(textElement("span", blocker.message || blocker.code));
      gateList.appendChild(row);
    });
    if (blockers.length === 0) {
      gateList.appendChild(textElement("div", "확정할 수 없습니다. 입력값과 검증 결과를 확인하세요."));
    }
  }

  function confirmPayment() {
    if (!paymentId || confirmButton.disabled) return;
    confirmButton.disabled = true;
    apiClient.request("/api/v1/transactions/" + encodeURIComponent(paymentId) + "/confirm", {
      method: "POST",
      idempotencyKey: "TRAN-CONFIRM-" + paymentId + "-" + Date.now()
    }).then(function () {
      document.getElementById("tx-status").textContent = "확정 · #" + paymentId;
      toast("수수료 지급 건을 확정했습니다.", "success", 3000);
      window.setTimeout(function () { window.location.assign("/transactions"); }, 900);
    }).catch(function (error) {
      showError(error, "수수료 지급 건을 확정하지 못했습니다.");
      precheckButton.disabled = false;
    });
  }

  function lockDraftInputs() {
    main.querySelectorAll("input, select").forEach(function (control) { control.disabled = true; });
    attrBody.querySelectorAll("button").forEach(function (button) { button.disabled = true; });
    addButton.disabled = true;
    saveButton.disabled = true;
  }

  function renderCapMessage(message) {
    var capBody = document.getElementById("cap-body");
    clear(capBody);
    var text = textElement("p", message);
    text.className = "fgc-muted";
    capBody.appendChild(text);
  }

  function statusSuffix(status) { return status === "VIOLATION" ? "(100% 초과)" : ""; }
  function statusColor(status) { return { NORMAL: "#22c55e", WARNING: "#f59e0b", VIOLATION: "#ef4444", REVIEW_REQUIRED: "#64748b" }[status] || "#64748b"; }
  function statusStyle(status) { return "font-size:12px;padding:3px 7px;border-radius:4px;color:" + statusColor(status) + ";border:1px solid " + statusColor(status); }
  function textElement(tag, text) { var element = document.createElement(tag); element.textContent = text; return element; }
  function toast(message, tone, duration) { if (window.FgcUi && typeof window.FgcUi.toast === "function") window.FgcUi.toast(message, tone, duration); }

  function buildPayload() {
    requireValue(sourceType, "원천유형"); requireValue(bizKey, "업무키");
    requireValue(settlementMonth, "정산월"); requireValue(recipient, "수령 설계사");
    requireValue(item, "수수료 항목"); requireValue(amount, "금액");
    var attributions = Array.from(attrBody.querySelectorAll("[data-attr-row]")).map(attributionPayload);
    if (attributions.length === 0) throw new Error("귀속행을 하나 이상 추가하세요.");
    return {
      sourceType: sourceType.value,
      sourceBusinessKey: bizKey.value.trim(),
      contractId: attributions[0].contractId,
      agentId: Number(recipient.value),
      commissionItemId: Number(item.value),
      amount: Number(amount.value),
      settlementMonth: monthFirstDay(),
      cashflowType: cashflow.value,
      scheduledPaymentDate: monthFirstDay(),
      paymentStage: stage.value,
      allocationPolicyVersion: null,
      attributions: attributions,
      evidenceRef: blankToNull(evidence.value),
      note: null
    };
  }

  function attributionPayload(row) {
    var contract = row.querySelector("[data-contract-id]");
    var date = row.querySelector("[data-attr-date]");
    var attrAmount = row.querySelector("[data-attr-amount]");
    requireValue(contract, "귀속 계약"); requireValue(date, "귀속일"); requireValue(attrAmount, "귀속금액");
    var decision = row.querySelector("[data-inclusion]").value;
    var exclusion = row.querySelector("[data-exclusion]").value;
    var rowEvidence = blankToNull(row.querySelector("[data-attr-evidence]").value);
    if (decision === "EXCLUDED" && (exclusion === "NONE" || !rowEvidence)) {
      throw new Error("제외 귀속행은 제외유형과 증빙을 입력해야 합니다.");
    }
    return {
      contractId: Number(contract.value), attributionDate: date.value,
      amount: Number(attrAmount.value), inclusionDecisionStatus: decision,
      exclusionType: exclusion, inclusionDecisionReason: decisionReason(decision),
      allocationBasis: blankToNull(row.querySelector("[data-allocation-basis]").value),
      evidenceRef: rowEvidence, attributionMethod: row.querySelector("[data-attr-method]").value
    };
  }

  function updateAttributionSummary() {
    var sum = Array.from(attrBody.querySelectorAll("[data-attr-amount]"))
      .reduce(function (total, input) { return total + number(input.value); }, 0);
    var difference = number(amount.value) - sum;
    document.getElementById("attr-sum").textContent = money(sum);
    var diff = document.getElementById("attr-diff");
    diff.textContent = difference === 0 ? "지급액과 일치" : "차액 " + money(difference);
    diff.style.color = difference === 0 ? "#15803d" : "#d92d20";
  }

  function suggestedAmount() {
    return attrBody.querySelector("[data-attr-row]") ? 0 : number(amount.value);
  }
  function selectedAgentLabel() {
    var option = recipient.options[recipient.selectedIndex];
    return option && option.value ? option.textContent : "—";
  }
  function monthFirstDay() { return settlementMonth.value ? settlementMonth.value + "-01" : ""; }
  function decisionReason(decision) {
    return { INCLUDED: "화면에서 산입으로 지정", EXCLUDED: "화면에서 제외로 지정", REVIEW_REQUIRED: "화면에서 검토필요로 지정" }[decision];
  }

  function generateBusinessKey() {
    var now = new Date();
    function two(value) { return String(value).padStart(2, "0"); }
    return "GA-MANUAL-" + now.getFullYear()
      + two(now.getMonth() + 1)
      + two(now.getDate()) + "-"
      + two(now.getHours())
      + two(now.getMinutes())
      + two(now.getSeconds()) + "-"
      + String(now.getMilliseconds()).padStart(3, "0");
  }

  function fillOptions(select, entries, placeholder) {
    clear(select);
    if (placeholder) addOption(select, "", placeholder);
    entries.forEach(function (entry) { addOption(select, entry[0], entry[1]); });
  }
  function addOption(select, value, text) { var option = document.createElement("option"); option.value = value; option.textContent = text; select.appendChild(option); }
  function input(type, value, marker) { var control = document.createElement("input"); control.type = type; control.value = value; if (marker) control.setAttribute(marker, ""); if (type === "number") { control.min = "0"; control.step = "1"; } return control; }
  function readonlyInput(value) { var control = input("text", value, "data-attr-month"); control.readOnly = true; return control; }
  function controlCell(control) { var cell = document.createElement("td"); cell.appendChild(control); return cell; }
  function appendTextCell(row, value) { var cell = document.createElement("td"); cell.textContent = value; row.appendChild(cell); }
  function renumberRows() { attrBody.querySelectorAll("[data-attr-row]").forEach(function (row, index) { row.firstElementChild.textContent = index + 1; }); }
  function renderEmptyAttributions() { var row = document.createElement("tr"); var cell = document.createElement("td"); var empty = document.createElement("div"); cell.colSpan = 12; empty.className = "fgc-empty"; empty.textContent = "귀속행을 추가하세요."; cell.appendChild(empty); row.appendChild(cell); attrBody.appendChild(row); }
  function requireValue(control, label) { if (!control.value || (control.type === "number" && number(control.value) < 0)) { control.focus(); throw new Error(label + "을(를) 확인하세요."); } }
  function showError(error, fallback) { var message = error && error.message ? error.message : fallback; if (error && error.field) message += " (" + error.field + ")"; window.alert(message); }
  function blankToNull(value) { var trimmed = String(value || "").trim(); return trimmed || null; }
  function number(value) { var parsed = Number(value); return Number.isFinite(parsed) ? parsed : 0; }
  function money(value) { return new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 }).format(number(value)) + "원"; }
  function clear(element) { while (element.firstChild) element.removeChild(element.firstChild); }
})();
